# Railway deployment

Arguslog production runs on a single Railway project (`arguslog-prod`) with one
service per process and Railway-managed Postgres + Redis. This file is a
P0 placeholder; real provisioning lands in **P5 (Launch readiness)**.

## Services in the Railway project

| Service          | Source path        | Builder  | Health check                  |
| ---------------- | ------------------ | -------- | ----------------------------- |
| `arguslog-api`      | `services/api/`    | Nixpacks | `/actuator/health/readiness`  |
| `arguslog-ingest`   | `services/ingest/` | Nixpacks | `/actuator/health/readiness`  |
| `arguslog-worker`   | `services/worker/` | Nixpacks | `/actuator/health/readiness`  |
| `arguslog-web`      | `apps/web/`        | Nixpacks | `/`                           |
| `arguslog-keycloak` | _Keycloak image_   | Docker   | `/health/ready` (port `9000`) |

Each service has a per-directory `railway.toml` co-located with its source so
Railway picks up the right build/start commands when its **Watch Paths**
match.

## Managed add-ons

| Add-on               | Plugin                                 | Notes                                                             |
| -------------------- | -------------------------------------- | ----------------------------------------------------------------- |
| Postgres + Timescale | `railway add postgresql + timescaledb` | Owns all Flyway migrations (api service).                         |
| Redis                | `railway add redis`                    | Used by ingest (Streams) + worker (consumer group) + api (cache). |
| Cloudflare R2        | external (S3-compatible)               | Source maps + attachments.                                        |

## Environments

- `production` — the only environment until P5; Stripe live keys live here.
- `preview` — auto-created per PR (P5 task), uses Stripe test keys + a
  detached Keycloak realm import.

## Required variables (per service)

Wire shared variables via Railway **Service Variables → Reference Variables**
so a single `${{Postgres.DATABASE_URL}}` propagates everywhere.

### `arguslog-api`

```
DATABASE_URL              = ${{Postgres.DATABASE_URL}}
REDIS_URL                 = ${{Redis.REDIS_URL}}
KEYCLOAK_ISSUER           = https://auth.arguslog.example/realms/arguslog
R2_ACCESS_KEY             = (from secrets manager)
R2_SECRET_KEY             = (from secrets manager)
R2_BUCKET                 = arguslog-attachments
STRIPE_API_KEY            = (live)
STRIPE_WEBHOOK_SECRET     = (live)
RESEND_API_KEY            = (live)
JAVA_TOOL_OPTIONS         = -XX:MaxRAMPercentage=75
```

### `arguslog-ingest`

```
REDIS_URL                 = ${{Redis.REDIS_URL}}
DATABASE_URL              = ${{Postgres.DATABASE_URL}}   # read-only DSN auth
JAVA_TOOL_OPTIONS         = -XX:MaxRAMPercentage=75
```

### `arguslog-worker`

```
DATABASE_URL              = ${{Postgres.DATABASE_URL}}
REDIS_URL                 = ${{Redis.REDIS_URL}}
JAVA_TOOL_OPTIONS         = -XX:MaxRAMPercentage=75
```

### `arguslog-web`

```
VITE_API_URL              = https://api.arguslog.example
VITE_KEYCLOAK_URL         = https://auth.arguslog.example
VITE_KEYCLOAK_REALM       = arguslog
```

## Deploy flow

1. `main` is protected; merges trigger Railway via the Railway GitHub App
   (Watch Paths split per service to avoid full-repo rebuilds).
2. CI must be green (PR workflow) before Railway will deploy.
3. Migrations run as part of `arguslog-api` start (`flyway.enabled=true`),
   gated by an advisory lock so multiple replicas can't race.
4. Health checks must pass within `healthcheck.timeout` for the deploy to
   be promoted.

## Local equivalent

`make dev` brings up everything from `infra/docker/docker-compose.yml` —
same images, same env-var names. Keep the `.env.example` and the Railway
variable list in sync; deviations cause the classic "works on my machine"
class of bugs.
