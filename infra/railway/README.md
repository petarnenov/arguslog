# Railway deployment

Argus runs on Railway: one project (`arguslog`) with two environments (`staging`, `production`),
four service deployments per environment, plus managed Postgres+Timescale and Redis. Web is
served by Caddy from a multi-stage Docker build; the three Spring Boot services run from a JRE
image built by their per-service Dockerfile.

## Services

| Railway service   | Source path        | Builder    | Health check                    |
| ----------------- | ------------------ | ---------- | ------------------------------- |
| `arguslog-api`    | `services/api/`    | Dockerfile | `/actuator/health/readiness`    |
| `arguslog-ingest` | `services/ingest/` | Dockerfile | `/actuator/health/readiness`    |
| `arguslog-worker` | `services/worker/` | Dockerfile | `/actuator/health/readiness`    |
| `arguslog-web`    | `apps/web/`        | Dockerfile | `/healthz` (Caddy `respond ok`) |

Each service has a `railway.toml` co-located with its source — Railway auto-detects them so
there's no per-service dashboard config to drift.

## Managed add-ons

| Add-on               | Plugin                                 | Notes                                                             |
| -------------------- | -------------------------------------- | ----------------------------------------------------------------- |
| Postgres + Timescale | `railway add postgresql + timescaledb` | Owns all Flyway migrations (api service).                         |
| Redis                | `railway add redis`                    | Used by ingest (Streams) + worker (consumer group) + api (cache). |
| Cloudflare R2        | external (S3-compatible)               | Source maps + attachments.                                        |

## First-time setup (operator runbook)

The production project is provisioned by hand once. Subsequent deploys are tag- or push-driven.

```bash
# 1. Authenticate the Railway CLI in this terminal.
railway login

# 2. Create the project + link this repo to it.
cd /path/to/argus
railway init --name arguslog
# Pick "empty project". The `railway link` step happens automatically after init.

# 3. Create the two environments.
railway environment new staging
railway environment new production

# 4. For each environment, add managed Postgres + Redis.
railway environment use staging
railway add --plugin postgresql --plugin redis
railway environment use production
railway add --plugin postgresql --plugin redis

# 5. Create the four services (per environment). The CLI infers the Dockerfile path from
#    services/<svc>/railway.toml, so service names are arbitrary but conventional:
for env in staging production; do
  railway environment use "$env"
  for svc in api ingest worker web; do
    if [ "$svc" = "web" ]; then
      railway service create "arguslog-web" --root-dir "."
    else
      railway service create "arguslog-$svc" --root-dir "."
    fi
  done
done

# 6. Wire reference variables (per service, per environment) — see "Variables" below.
#    Use the dashboard for the secrets-bearing ones (Stripe keys, etc.) so they never hit a
#    shell history.
```

Once the project exists, generate a project-scoped token from the dashboard and store it as a
GitHub Actions secret named `RAILWAY_TOKEN_STAGING` (production gets its own). The
`.github/workflows/deploy-staging.yml` workflow uses it on every push to `main`.

## Variables (per service)

Wire shared values via Railway **Service Variables → Reference Variables** so a single
`${{Postgres.DATABASE_URL}}` propagates through every service without manual sync.

### `arguslog-api`

```
DATABASE_URL              = ${{Postgres.DATABASE_URL}}
REDIS_URL                 = ${{Redis.REDIS_URL}}
KEYCLOAK_ISSUER           = https://auth.arguslog.org/realms/arguslog
R2_ENDPOINT               = (Cloudflare R2 endpoint URL)
R2_ACCESS_KEY             = (from Cloudflare R2 console)
R2_SECRET_KEY             = (from Cloudflare R2 console)
R2_BUCKET                 = arguslog-attachments
STRIPE_API_KEY            = (live or test depending on env)
STRIPE_WEBHOOK_SECRET     = (signing secret for /api/v1/webhooks/stripe)
STRIPE_PRICE_PRO          = (price_xxx for the Pro tier)
DASHBOARD_BASE_URL        = https://app.arguslog.org
RESEND_API_KEY            = (Resend API key)
INGEST_PUBLIC_HOST        = https://ingest.arguslog.org
CORS_ORIGINS              = https://app.arguslog.org
JAVA_TOOL_OPTIONS         = -XX:MaxRAMPercentage=75
```

### `arguslog-ingest`

```
DATABASE_URL              = ${{Postgres.DATABASE_URL}}   # read-only DSN auth
REDIS_URL                 = ${{Redis.REDIS_URL}}
JAVA_TOOL_OPTIONS         = -XX:MaxRAMPercentage=75
```

### `arguslog-worker`

```
DATABASE_URL              = ${{Postgres.DATABASE_URL}}
REDIS_URL                 = ${{Redis.REDIS_URL}}
R2_ENDPOINT               = (same as api)
R2_ACCESS_KEY             = (same as api)
R2_SECRET_KEY             = (same as api)
R2_BUCKET                 = arguslog-attachments
TELEGRAM_BOT_TOKEN        = (Telegram bot token, optional)
RESEND_API_KEY            = (Resend API key, optional)
RESEND_FROM               = alerts@arguslog.org
RETENTION_DRY_RUN         = true   # flip to false after one dry run
JAVA_TOOL_OPTIONS         = -XX:MaxRAMPercentage=75
```

### `arguslog-web`

Build-time (baked into the bundle — change requires rebuild):

```
VITE_API_BASE_URL         = https://api.arguslog.org
VITE_KEYCLOAK_URL         = https://auth.arguslog.org
VITE_KEYCLOAK_REALM       = arguslog
VITE_KEYCLOAK_CLIENT_ID   = arguslog-web
```

Runtime: just `PORT` (Railway-injected; Caddy reads it).

## Deploy flow

1. `main` is protected; merges trigger
   [`.github/workflows/deploy-staging.yml`](../../.github/workflows/deploy-staging.yml) which
   runs `railway up --detach` per service against the `staging` environment.
2. Production deploys are explicit — promote via the Railway dashboard's "Promote to
   production" action, or run `railway up --environment=production --service=...` manually.
3. Migrations run as part of `arguslog-api` start (`flyway.enabled=true`), gated by
   Flyway's advisory lock so multiple replicas can't race on the first boot.
4. Health checks must pass within `healthcheckTimeout` for the deploy to be promoted.

## Custom domains (P5 #7)

Set in Railway → Service → Settings → Domains. Cloudflare DNS for `arguslog.org`:

| Subdomain               | Service           | Cloudflare proxy? | Reason                                                                 |
| ----------------------- | ----------------- | ----------------- | ---------------------------------------------------------------------- |
| `app.arguslog.org`      | `arguslog-web`    | on                | WAF / DDoS for the user-facing dashboard.                              |
| `api.arguslog.org`      | `arguslog-api`    | on                | Same.                                                                  |
| `ingest.arguslog.org`   | `arguslog-ingest` | **off**           | Avoid double-hop on every event POST; cf doesn't help an authed write. |
| `auth.arguslog.org`     | Keycloak          | on                | OIDC issuer; cf is fine in front.                                      |

Railway provisions Let's Encrypt certificates automatically for each custom domain.

## Local equivalent

`infra/docker/docker-compose.yml` brings up the same images (rebuilt with the same Dockerfiles).
Keep `.env.example` in sync with the variable lists above so the "works on my machine" class of
bugs stays rare.
