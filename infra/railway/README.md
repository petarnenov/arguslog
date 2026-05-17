# Railway deployment

Arguslog runs on Railway: one project (`arguslog`) with two environments (`staging`, `production`),
four service deployments per environment, plus managed Postgres+Timescale and Redis. Web is
served by Caddy from a multi-stage Docker build; the three Spring Boot services run from a JRE
image built by their per-service Dockerfile.

## Services

| Railway service        | Source path            | Builder    | Health check                    |
| ---------------------- | ---------------------- | ---------- | ------------------------------- |
| `arguslog-api`         | `services/api/`        | Dockerfile | `/actuator/health/readiness`    |
| `arguslog-ingest`      | `services/ingest/`     | Dockerfile | `/actuator/health/readiness`    |
| `arguslog-worker`      | `services/worker/`     | Dockerfile | `/actuator/health/readiness`    |
| `arguslog-web`         | `apps/web/`            | Dockerfile | `/healthz` (Caddy `respond ok`) |
| `arguslog-keycloak`    | `services/keycloak/`   | Dockerfile | `/realms/master` (8080)         |
| `arguslog-keycloak-db` | image `postgres:16`    | Image      | `pg_isready -U postgres`        |
| `arguslog-landing`     | `apps/landing/`        | Dockerfile | `/healthz` (Caddy `respond ok`) |
| `arguslog-mcp`         | `packages/mcp-server/` | Dockerfile | `/healthz`                      |

Each service has a `railway.toml` co-located with its source — Railway auto-detects them so
there's no per-service dashboard config to drift. The list above is mirrored by
`find . -name railway.toml -not -path '*/node_modules/*'`; if you add a new service, drop a
`railway.toml` next to its source and update this table in the same commit.
`arguslog-keycloak-db` is the exception — it is an image-only service with no repo source, so
it has no `railway.toml`. See "Keycloak backing store" below for its configuration.

## Managed add-ons

| Add-on               | Plugin                                 | Notes                                                             |
| -------------------- | -------------------------------------- | ----------------------------------------------------------------- |
| Postgres + Timescale | `railway add postgresql + timescaledb` | Owns all Flyway migrations (api service). App services only.      |
| Redis                | `railway add redis`                    | Used by ingest (Streams) + worker (consumer group) + api (cache). |
| Cloudflare R2        | external (S3-compatible)               | Source maps + attachments.                                        |

> Keycloak does NOT share the app-tier Postgres. Its backing store is the dedicated
> `arguslog-keycloak-db` image-based service with a Railway Volume on
> `/var/lib/postgresql/data`. See the "Keycloak backing store" section near the bottom of this
> file for the rationale + migration runbook for staging.

## Current state — production live (P5 #7 ✅)

Project id `f24cb7e5-c1fd-4520-a04d-dea1acd0d309`. All public custom domains
(`app.arguslog.org`, `ingest.arguslog.org`, `arguslog.org`, `mcp.arguslog.org`, plus Keycloak
behind `auth.arguslog.org`) are answering 200 on their health endpoints; Keycloak realm
import + email verification flow are live; Cloudflare R2 wired for attachments + source maps.

| Subdomain             | DNS in Cloudflare               | Cloudflare proxy | Health endpoint                                         |
| --------------------- | ------------------------------- | ---------------- | ------------------------------------------------------- |
| `app.arguslog.org`    | CNAME → 8onbll5q.up.railway.app | ON (orange)      | `/healthz` 200                                          |
| `api.arguslog.org`    | CNAME → 4j1n7gex.up.railway.app | ON (orange)      | `/actuator/health/readiness` 200                        |
| `auth.arguslog.org`   | CNAME → cymu37i0.up.railway.app | ON (orange)      | `/realms/arguslog/.well-known/openid-configuration` 200 |
| `ingest.arguslog.org` | CNAME → d9fz3gra.up.railway.app | OFF (grey)       | `/actuator/health/readiness` 200                        |

Cloudflare zone-wide settings: **SSL/TLS mode = Full** (required by Railway custom-domain TLS;
"Flexible" mode breaks origin handshakes). Resend DKIM/SPF/DMARC TXT + MX records also live in
the same zone — keep them when re-applying DNS templates.

Railway-issued direct URLs are still active and useful for bypassing Cloudflare during incident
debug:

| Service             | Direct URL                                          |
| ------------------- | --------------------------------------------------- |
| `arguslog-api`      | https://arguslog-api-production.up.railway.app      |
| `arguslog-ingest`   | https://arguslog-ingest-production.up.railway.app   |
| `arguslog-web`      | https://arguslog-web-production.up.railway.app      |
| `arguslog-keycloak` | https://arguslog-keycloak-production.up.railway.app |

## Staging

| Service                | Status                | Public URL                                                |
| ---------------------- | --------------------- | --------------------------------------------------------- |
| `arguslog-api`         | ✅ deployed           | https://arguslog-api-staging.up.railway.app               |
| `arguslog-ingest`      | ✅ deployed           | https://arguslog-ingest-staging.up.railway.app            |
| `arguslog-worker`      | ✅ deployed           | _internal — no public domain_                             |
| `arguslog-web`         | ✅ deployed           | https://arguslog-web-staging.up.railway.app               |
| `arguslog-keycloak`    | ✅ deployed           | https://arguslog-keycloak-staging.up.railway.app          |
| `arguslog-keycloak-db` | ✅ running (image)    | _internal — `arguslog-keycloak-db.railway.internal:5432`_ |
| `timescaledb`          | ✅ running (image)    | _internal — `timescaledb.railway.internal:5432`_          |
| `Redis`                | ✅ running (template) | _internal — `redis.railway.internal:6379`_                |

### Production-only services (intentionally offline in staging)

The following services exist project-wide but have `source: null` on the staging instance —
their per-env source has never been assigned, so the staging dashboard shows "Service is
offline". This is by design; no staging value to add given the role of each service. If a
future change demands staging coverage, mirror the production source via GraphQL
`serviceInstanceUpdate(input: { source: { ... } })` then trigger an initial deploy with a
no-op variable write.

- **`arguslog-mcp`** — public hosted MCP server on `mcp.arguslog.org`. Per-request PAT auth +
  the published `@arguslog/mcp-server` npm package mean no staging gap exists in the integration
  test surface (self-host-smoke CI exercises the MCP HTTP path inside Docker compose). No
  Cloudflare proxy fronts staging, so the `CF_ORIGIN_TOKEN` guard wouldn't function there
  anyway.
- **`arguslog-landing`** — marketing site on apex `arguslog.org` (CNAME). Static Caddy build
  with zero per-env behavior; serving a staging copy adds nothing.

Open follow-ups (deferred — none block #5/#6 starting):

- ~~**Keycloak service.**~~ Done — `arguslog-keycloak` is live with `auth.arguslog.org` and
  realm import working. Backing store moved off the auto-provisioned plugin onto the dedicated
  `arguslog-keycloak-db` image-based Postgres + Railway Volume; see "Keycloak backing store"
  below.
- **Stale plain Postgres plugin services** — the post-migration plugins (`Postgres` with
  `postgres-volume-J1m2`) on both staging and production are intentionally kept until
  2026-05-16 14:00 UTC as a rollback safety net. Delete via dashboard after the soak window
  closes (CLI has no service-delete primitive). The pre-migration orphan volumes
  (`postgres-volume`, `redis-volume-G0e_`) that were left behind by earlier setup attempts
  were **deleted by the operator on 2026-05-15** — both staging and production volume lists
  are now clean apart from the attached `redis-volume`, `arguslog-keycloak-db-volume`,
  `postgres-volume-J1m2` (rollback), and `timescaledb-volume` (where applicable).
- ~~**Stripe live keys.**~~ Obsolete — OSS conversion removed payments.
- **R2 buckets — per-environment isolation** (separated 2026-05-15):
  - **Production:** `arguslog-attachments` (WEUR). Wired to api + worker via
    `R2_ENDPOINT` + `R2_ACCESS_KEY` + `R2_SECRET_KEY` + `R2_BUCKET`. Token unchanged from the
    pre-isolation setup; still scoped to this bucket (was already so by Cloudflare default).
  - **Staging:** `arguslog-staging-attachments` (WEUR). Different bucket + a **separate**
    scoped R2 API token. Verified post-cutover: the staging token returns `AccessDenied` when
    pointed at the production bucket — compromise of any staging service no longer reaches
    production sourcemaps / attachments.
  - **Endpoint** is account-level (`<account>.r2.cloudflarestorage.com`) and therefore
    identical across both envs; only `R2_BUCKET` + the credentials differ. Rollback creds for
    staging are at `/tmp/r2-rollback-staging.env` until the soak window closes.
- **Resend SMTP** is wired into the Keycloak realm via the admin API (host=smtp.resend.com,
  port=465 SSL, from=noreply@arguslog.org). The `services/keycloak/realm/arguslog-realm.json`
  file still references the docker-compose `mailhog` host so local dev keeps working — prod
  overrides live in Keycloak, not the file. Re-import after a clean DB reset will need the
  SMTP block patched again via `PUT /admin/realms/arguslog`.
- **Demo seeded user** in the realm import (`demo@arguslog.local` / `demo`). Delete or rotate
  before sharing the dashboard publicly.
- **Production environment.** All services exist there but with no variables and no first
  deploy. Mirror staging's `railway variables --set` calls under `--environment production`
  before promoting (#7).
- **`RAILWAY_TOKEN_STAGING` GitHub Action secret.** Generate a project-scoped token in the
  dashboard and add it to repo secrets so `.github/workflows/deploy-staging.yml` can run.

## First-time setup (operator runbook)

The production project is provisioned by hand once. Subsequent deploys are tag- or push-driven.

```bash
# 1. Authenticate the Railway CLI in this terminal.
railway login

# 2. Create the project + link this repo to it.
cd /path/to/arguslog
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

# 6. Create the dedicated Keycloak Postgres + attach a Volume (per environment).
#    This service is image-only — no railway.toml in the repo. Create it via the dashboard
#    or `railway service create --image postgres:16`. Then in the dashboard:
#    Settings → Volumes → Add → mount path `/var/lib/postgresql/data`, name `keycloak-data`.
#    Set service variables POSTGRES_USER / POSTGRES_PASSWORD / POSTGRES_DB.

# 7. Wire reference variables (per service, per environment) — see "Variables" below.
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
R2_ENDPOINT               = (Cloudflare R2 endpoint URL — account-level, same in both envs)
R2_ACCESS_KEY             = (per-env scoped R2 API token — DIFFERENT in staging vs prod)
R2_SECRET_KEY             = (per-env scoped R2 API token — DIFFERENT in staging vs prod)
R2_BUCKET                 = arguslog-attachments         # production
                          # arguslog-staging-attachments  # staging
ARGUSLOG_ALERTS_SECRET_KEY = (base64-encoded 32-byte AES-256 master key — see
                              "Secret cipher master key" section below; empty value
                              falls back to the OSS-public dev key and prints a loud WARN)
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
R2_ENDPOINT               = (same as arguslog-api in this env)
R2_ACCESS_KEY             = (same as arguslog-api in this env — per-env scoped token)
R2_SECRET_KEY             = (same as arguslog-api in this env — per-env scoped token)
R2_BUCKET                 = arguslog-attachments         # production
                          # arguslog-staging-attachments  # staging
ARGUSLOG_ALERTS_SECRET_KEY = (same value as arguslog-api in this env; api + worker MUST share
                              the key — api encrypts new alert destinations on write, worker
                              decrypts on dispatch. A mismatch silently breaks delivery.)
RETENTION_DRY_RUN          = false   # was true until 2026-05-15
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

### `arguslog-keycloak`

```
KC_DB                       = postgres
KC_DB_URL                   = jdbc:postgresql://${{arguslog-keycloak-db.RAILWAY_PRIVATE_DOMAIN}}:5432/${{arguslog-keycloak-db.POSTGRES_DB}}
KC_DB_USERNAME              = ${{arguslog-keycloak-db.POSTGRES_USER}}
KC_DB_PASSWORD              = ${{arguslog-keycloak-db.POSTGRES_PASSWORD}}
KC_HOSTNAME                 = auth.arguslog.org   # staging: leave unset to use Railway-issued domain
KC_BOOTSTRAP_ADMIN_USERNAME = (initial admin; rotate after first login)
KC_BOOTSTRAP_ADMIN_PASSWORD = (initial admin password; rotate after first login)
```

> Reference Variables (`${{arguslog-keycloak-db.*}}`) keep the connection string in lockstep
> with whatever password / domain the Postgres service exposes; no manual copy-paste between
> services.

### `arguslog-keycloak-db`

Image-only service (`postgres:16`). Required variables:

```
POSTGRES_USER             = keycloak
POSTGRES_PASSWORD         = (generated; treat as a secret)
POSTGRES_DB               = keycloak
PGDATA                    = /var/lib/postgresql/data/pgdata   # subdir prevents lost+found clashes
```

Volume: mount `keycloak-data` at `/var/lib/postgresql/data` (dashboard → Settings → Volumes).
The `PGDATA` env points one level deeper so Postgres can boot on a fresh volume that already
contains the FS metadata directory.

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

| Subdomain             | Service           | Cloudflare proxy? | Reason                                                                 |
| --------------------- | ----------------- | ----------------- | ---------------------------------------------------------------------- |
| `app.arguslog.org`    | `arguslog-web`    | on                | WAF / DDoS for the user-facing dashboard.                              |
| `api.arguslog.org`    | `arguslog-api`    | on                | Same.                                                                  |
| `ingest.arguslog.org` | `arguslog-ingest` | **off**           | Avoid double-hop on every event POST; cf doesn't help an authed write. |
| `auth.arguslog.org`   | Keycloak          | on                | OIDC issuer; cf is fine in front.                                      |

Railway provisions Let's Encrypt certificates automatically for each custom domain.

## Keycloak backing store

Keycloak's database holds two classes of data that the realm-import JSON does NOT cover and
which therefore CANNOT be regenerated by redeploying:

- Runtime-applied SMTP overrides (production points realm SMTP at Resend via admin API; the
  committed `services/keycloak/realm/arguslog-realm.json` keeps `mailhog` for local dev). See
  the `project_keycloak_smtp` memory note.
- Live user accounts, sessions, federated-identity links, password hashes, login history.
- Admin password rotations after the bootstrap user is replaced.

That is why the backing store is a dedicated `arguslog-keycloak-db` Postgres service with an
attached Railway Volume — both per environment. It must survive any churn that would prune an
auto-provisioned plugin instance: template re-provision, plugin downgrade/upgrade, accidental
service delete on the wrong tab.

### Rationale recap

| Concern                     | Plugin Postgres (old)                  | Dedicated image + Volume (new)               |
| --------------------------- | -------------------------------------- | -------------------------------------------- |
| Lifecycle ownership         | Railway-managed, can be pruned         | Operator-owned; explicit volume attachment   |
| Co-tenancy with app data    | Shared with `Postgres` plugin          | Isolated; backup of app DB never includes KC |
| Restore on data loss        | Re-import realm + reapply SMTP by hand | `pg_restore` from a `pg_dump` snapshot       |
| Production / staging parity | Same plugin in both envs               | Same image + volume layout in both envs      |

### Staging migration — executed 2026-05-15

Performed end-to-end against the live staging Railway project. Service IDs + observed
behavior captured below so the production runbook below can reuse them.

| Step | Tool                                                                                                                                                                                      | Outcome                                                                                                                                                                                                                                                    |
| ---- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| 0    | `railway variables --service arguslog-keycloak --kv`                                                                                                                                      | Confirmed old plugin `Postgres` host = `postgres.railway.internal`, db `railway`, user `postgres`                                                                                                                                                          |
| —    | `psql ... SELECT version()` through the plugin's public proxy                                                                                                                             | Plugin runs **PostgreSQL 18.3** — production migration must use `postgres:18` image to match the major version, NOT `postgres:16`                                                                                                                          |
| 1    | `railway add --service arguslog-keycloak-db --image postgres:18 --variables`                                                                                                              | Service ID `6d1b83f5-ec27-49f0-b094-79da89e012b3` created with `POSTGRES_USER=keycloak`, `POSTGRES_DB=keycloak`, `PGDATA=/var/lib/postgresql/data/pgdata` + 39-char random password                                                                        |
| 2    | `railway service link arguslog-keycloak-db` → `railway volume add --mount-path /var/lib/postgresql/data`                                                                                  | Volume `arguslog-keycloak-db-volume` (50 GB cap, 0 MB used at start) attached; auto-redeploy fired                                                                                                                                                         |
| 3    | `railway ssh --service arguslog-keycloak-db 'pg_dump -Fc ...                                                                                                                              | pg_restore ...'`                                                                                                                                                                                                                                           | EXIT=0; entire pipeline ran inside Railway network — no public exposure required for data transit |
| 4    | GraphQL `tcpProxyCreate(input:{...applicationPort:5432})` (CLI v4.30.2 doesn't have a `tcp-proxy` subcommand; HTTP `railway domain` is the wrong tool)                                    | Temp proxy `trolley.proxy.rlwy.net:29951` created so target row-counts could be queried from outside Railway                                                                                                                                               |
| 5    | `psql ... SELECT count(*)` per critical table                                                                                                                                             | 100% row-count parity on 10 critical tables: 2 realms, 4 user_entity, 3 credentials, 15 clients, 10 realm_smtp_config (Resend overrides), 150 databasechangelog, 416 protocol_mapper_config, 39 authentication_flow, 5 user_role_mapping, 83 keycloak_role |
| 6    | `railway variables --service arguslog-keycloak --set "KC_DB_URL=jdbc:postgresql://arguslog-keycloak-db.railway.internal:5432/keycloak" --set KC_DB_USERNAME=... --set KC_DB_PASSWORD=...` | Auto-triggered redeploy. Reference-variable form (`${{arguslog-keycloak-db.*}}`) is documented in the per-service Variables block above but was NOT used here — hardcoded literals were chosen so the cutover failure mode is easier to grep               |
| 7    | Wait loop on `railway service status` until status != BUILDING/DEPLOYING                                                                                                                  | Final status `SUCCESS` after ~2 min                                                                                                                                                                                                                        |
| 8    | `curl https://arguslog-keycloak-staging.up.railway.app/realms/arguslog/.well-known/openid-configuration`                                                                                  | Issuer + token endpoint live; KC booted in **27.964 s** on the new DB; zero error/exception/fatal lines in deployment logs                                                                                                                                 |
| 9    | GraphQL `tcpProxyDelete` + `serviceDomainDelete`                                                                                                                                          | Temp TCP proxy + the unused HTTP domain created during exploration both removed; service is now private-only                                                                                                                                               |
| 10   | Stale plugin `Postgres` left running                                                                                                                                                      | Kept until 2026-05-16 14:00 UTC as rollback safety net; rollback creds saved at `/tmp/kc-rollback.env`                                                                                                                                                     |

### Lessons learned (apply to production runbook below)

- **`railway ssh` hangs in non-interactive shells** when stdin is `/dev/null` (e.g. Bash
  background mode in this environment, CI, `nohup`). Run dump/restore from a shell that has
  a TTY, or schedule it as a Railway cron / one-shot job instead.
- **CLI v4.30.2 has no `tcp-proxy` subcommand.** Use the GraphQL mutation
  `tcpProxyCreate(input: { serviceId, environmentId, applicationPort })` via
  `curl https://backboard.railway.com/graphql/v2` with the bearer token read from
  `~/.railway/config.json` (key path `user.token`). Same for `tcpProxyDelete(id)` and
  `serviceDomainDelete(id)`.
- **`railway connect <service>` requires `DATABASE_PUBLIC_URL` on the target service.** It is
  auto-populated only after a TCP proxy exists. Useless for fresh private-only DBs.
- **Setting variables auto-redeploys** unless `--skip-deploys` is passed. The cutover does not
  need a separate `railway up` call.

### Production migration — executed 2026-05-15

Performed end-to-end immediately after the staging migration. Same procedure, with the
deltas noted below. Service IDs + observed behavior captured for any future re-run.

| Step | Tool                                                                                                                                                  | Outcome                                                                                                                                                                                                                 |
| ---- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------- |
| 0    | `railway environment production` + `railway variables --service arguslog-keycloak --kv`                                                               | Old plugin host `postgres.railway.internal`, db `railway`, user `postgres`. Plugin version verified **PostgreSQL 18.3** via public proxy.                                                                               |
| 1a   | `railway add --service arguslog-keycloak-db --image postgres:18 --variables ...`                                                                      | Failed: "service already exists" — same `arguslog-keycloak-db` service was created in staging, and Railway service names are project-scoped.                                                                            |
| 1b   | `railway variables --service arguslog-keycloak-db --environment production --set ...` (POSTGRES\_\*+PGDATA)                                           | Set vars on the production-environment instance of the shared service.                                                                                                                                                  |
| 1c   | `railway volume add --mount-path /var/lib/postgresql/data`                                                                                            | Volume `arguslog-keycloak-db-volume-yjaD` (50 GB cap) attached to the production instance.                                                                                                                              |
| 1d   | Status `NO DEPLOYMENT`. GraphQL `serviceInstanceUpdate(input: { source: { image: "postgres:18" } })` then a benign var write `BOOT_TRIGGER=1`         | The production service instance had `source: null` (the staging instance had `image: postgres:18`). Setting source + triggering a var change kicked the first deploy. Status `SUCCESS`.                                 |
| 2    | `railway ssh --service arguslog-keycloak-db --environment production 'pg_dump -Fc ...                                                                 | pg_restore ...'`                                                                                                                                                                                                        | EXIT=0; inline pipe inside Railway network. |
| 3    | GraphQL `tcpProxyCreate(...applicationPort:5432)` → `yamanote.proxy.rlwy.net:45232`, then row-count query                                             | 100% parity on 10 critical KC tables: 2/5/4/15/10/150/416/39/6/83 (realm/user_entity/credential/client/realm_smtp_config/databasechangelog/protocol_mapper_config/authentication_flow/user_role_mapping/keycloak_role). |
| 4    | `railway variables --service arguslog-keycloak --environment production --set "KC_DB_URL=..." --set KC_DB_USERNAME=keycloak --set KC_DB_PASSWORD=...` | Auto-triggered redeploy; final status `SUCCESS`. KC booted in **25.238 s** on the new DB (faster than staging's 27.964 s).                                                                                              |
| 5    | `curl https://auth.arguslog.org/realms/arguslog/.well-known/openid-configuration`                                                                     | Issuer `https://auth.arguslog.org/realms/arguslog` + token endpoint live; **no Cloudflare 502 observed** during the cutover.                                                                                            |
| 6    | GraphQL `tcpProxyDelete` + `railway variable delete BOOT_TRIGGER`                                                                                     | Temp TCP proxy removed; service-instance vars trimmed back to 4 production-relevant keys (POSTGRES_USER/PASSWORD/DB + PGDATA).                                                                                          |
| 7    | Stale plugin `Postgres` left running                                                                                                                  | Kept until 2026-05-16 14:00 UTC as rollback safety net; rollback creds saved at `/tmp/kc-rollback-prod.env`.                                                                                                            |

### Lessons learned (production-specific, on top of staging's)

- **Project-scoped service names.** `railway add --service <name>` fails with "service already
  exists" if a service of that name lives in any environment in the project. Fix: switch to
  `railway variables --set ...` + `railway volume add` against the existing service in the
  target environment.
- **`source: null` on a fresh per-env instance blocks the first deploy.** When a service is
  created in one environment, sibling environments get a service instance with `source: null`.
  Setting variables alone does not trigger a deploy — the instance needs `source` set first.
  Use GraphQL `serviceInstanceUpdate(environmentId, serviceId, input: { source: { image:
"<img>" } })`, then a no-op variable write to fire the initial deploy.
- **Cloudflare behaved.** No 502 observed during the KC restart, despite the proxy being ON
  for `auth.arguslog.org`. The 30-second Cloudflare-stale-502 risk noted in the staging-side
  lessons did not materialize — likely because Railway's healthcheck delay kept the old pod
  serving until the new one was ready.

## Secret cipher master key

`AesGcmSecretCipher` (`lib/crypto-aes-gcm/`) encrypts two production-sensitive columns:

- `alert_destinations.config_encrypted` — Telegram bot tokens, Slack webhook URLs, email
  recipient addresses, generic-webhook bearer tokens.
- `slack_workspaces.install_token_encrypted` — Slack bot OAuth tokens (`xoxb-...`).

The cipher reads `arguslog.alerts.secret-key` (env var `ARGUSLOG_ALERTS_SECRET_KEY`) — a
**base64-encoded 32-byte AES-256 master key**. If the var is empty, the cipher loudly falls
back to a built-in dev key whose plaintext is in the OSS source — _every_ encrypted column
in such a deploy is publicly decryptable. The boot WARN reads:

```
AesGcmSecretCipher: base64 master key is empty — using the built-in dev key. DO NOT run prod like this.
```

### Generate + apply

```bash
KEY=$(openssl rand -base64 32)
railway variables --service arguslog-api    --environment <env> --set "ARGUSLOG_ALERTS_SECRET_KEY=$KEY"
railway variables --service arguslog-worker --environment <env> --set "ARGUSLOG_ALERTS_SECRET_KEY=$KEY"
# Both services MUST share the value. api writes, worker reads.
```

Setting the variable auto-redeploys both services. After the redeploy, the dev-key WARN line
disappears from the boot logs.

### Rotation impact

Rotating the master key invalidates every existing ciphertext. The dashboard's encrypt path
only ever uses the _current_ key — there is no automatic decrypt-rewrite migration. Plan
accordingly:

- Pre-rotation: count rows in both encrypted columns with `SELECT count(*) FROM
alert_destinations` and `SELECT count(*) FROM slack_workspaces WHERE install_token_encrypted
IS NOT NULL`. Whatever ciphertext is in those columns at rotation time becomes garbage.
- Post-rotation: every operator/user whose alert destination or Slack workspace lived in the
  table must recreate it in the dashboard. Old rows fail decryption silently — the worker
  log-and-drops the alert dispatch.

If zero-data-loss is a hard requirement, the proper rotation path is a one-shot Java
migration utility (decrypt with old key, encrypt with new key, UPDATE). Not implemented as
of 2026-05-15 — see backlog if/when revisited.

### Executed 2026-05-15 (key set on all four api+worker × staging+prod instances)

- Old master key: built-in OSS dev key (`arguslog-dev-fallback-key-32byte`).
- New master key: 32-byte random, stored in operator secrets (and at
  `/tmp/arguslog-secret-cipher-key.txt` for the duration of this session).
- Post-rotation impact: staging held 0 encrypted rows → zero loss; production had 2 stale
  email alert destinations (`alert_destinations` ids 1 + 2, orgs `geo-mini` + `geowealth`) —
  documented at `/tmp/alert-destinations-to-recreate.md`; operator should recreate them in
  the dashboard. No Slack workspaces to lose (0 rows in both envs).

## Retention purge — live mode (since 2026-05-15)

`RETENTION_DRY_RUN=false` set on both staging and production after a dry-run emulation
confirmed every org's owner currently sits at `platinum` tier (365-day retention floor) —
worker finds 0 orgs below the floor and the purge loop is a no-op until an admin grant
downgrades someone to `gold` (90 d), `silver` (30 d) or `regular` (30 d). The flip is a
hygiene change so the deploy is "correctly configured for the steady state" rather than
"forever in dry-run mode by default".

The TimescaleDB hypertable retention policy (chunk drop at 365 days) still handles the
no-org-below-floor case automatically — set in `services/api/src/main/resources/db/migration/V10__events_retention_policy.sql`.

### Post-migration cleanup (scheduled 2026-05-16 14:00 UTC)

After 24 hours of healthy operation on the new dedicated DB, the operator should:

1. Delete the old plugin `Postgres` services on both staging and production (dashboard only;
   CLI has no service-delete primitive). They still hold a copy of the pre-cutover KC data
   on `postgres-volume-J1m2`.
2. Remove the local rollback files: `rm /tmp/kc-rollback.env /tmp/kc-rollback-prod.env
/tmp/keycloak-db-pw.txt /tmp/keycloak-db-pw-prod.txt /tmp/r2-rollback-staging.env
/tmp/arguslog-secret-cipher-key.txt /tmp/alert-destinations-to-recreate.md`.
3. (Already done 2026-05-15 by the operator) Pre-migration orphan volumes `postgres-volume`
   and `redis-volume-G0e_` deleted from both environments.
4. Recreate the 2 stale email alert destinations in production
   (`/tmp/alert-destinations-to-recreate.md` lists org slugs + names).

## Local equivalent

`infra/docker/docker-compose.yml` brings up the same images (rebuilt with the same Dockerfiles).
Keep `.env.example` in sync with the variable lists above so the "works on my machine" class of
bugs stays rare.
