# Self-hosting Arguslog

Run the full Arguslog stack on your own infrastructure. The shape of the
deployment is six containers: `api`, `ingest`, `worker`, `web`, `keycloak`,
plus a Postgres+TimescaleDB and a Redis. For sourcemap symbolication you
also need an S3-compatible object store (MinIO works locally; Cloudflare R2
or AWS S3 work in prod).

## Quick start (docker-compose)

```bash
git clone https://github.com/petarnenov/arguslog.git
cd arguslog
cp .env.example .env                # edit secrets — required
docker compose -f infra/docker/docker-compose.full.yml up -d
```

The compose file at `infra/docker/docker-compose.full.yml` wires everything
together. The shipped `.env.example` lists every var the stack reads — the
ones you actually have to set yourself are the secrets (DB password, JWT
issuer, S3 keys) and the platform-admin email.

Once it's up:

- Dashboard: `http://localhost:5173`
- API: `http://localhost:8081`
- Ingest: `http://localhost:8080`
- Keycloak admin console: `http://localhost:8180` (use the bootstrap admin
  you set in `.env`)
- MinIO console: `http://localhost:9001` (default `minioadmin` / `minioadmin`)
- Mailhog UI: `http://localhost:8025` (catches invite + admin emails in dev)

## Environment variables

Every env var the stack reads has a sensible localhost default. The ones
below either have NO default (so the service won't boot without them) or
have a default that's only safe for local dev:

| Variable                              | Used by    | Notes                                                                                  |
| ------------------------------------- | ---------- | -------------------------------------------------------------------------------------- |
| `DATABASE_URL`                        | api/worker | `jdbc:postgresql://host:5432/arguslog`                                                 |
| `DATABASE_USER` / `DATABASE_PASSWORD` | api/worker | Postgres credentials                                                                   |
| `REDIS_URL`                           | api/worker | `redis://host:6379` (events stream)                                                    |
| `KEYCLOAK_ISSUER`                     | api/web    | OIDC issuer URL — must match the realm Keycloak boots with                             |
| `ARGUSLOG_DEFAULT_TIER`               | api        | `regular` (default), `silver`, `gold`, or `platinum`. Set `platinum` for unlimited.    |
| `ARGUSLOG_PLATFORM_ADMINS`            | api        | Comma-separated admin emails. Matches the JWT `email` claim.                           |
| `ARGUSLOG_INITIAL_ADMIN_EMAIL`        | keycloak   | First-boot platform admin email — added to the realm + the allowlist above.            |
| `ARGUSLOG_INITIAL_ADMIN_PASSWORD`     | keycloak   | First-boot password the admin uses to sign in once; change immediately after.          |
| `R2_ENDPOINT` / `R2_BUCKET`           | api/worker | S3-compatible object store for sourcemaps. MinIO works in dev; R2 / S3 in prod.        |
| `R2_ACCESS_KEY` / `R2_SECRET_KEY`     | api/worker | object-store credentials                                                               |
| `RESEND_API_KEY`                      | worker     | Optional — alert + invite emails. Falls back to log-and-drop when empty.               |
| `TELEGRAM_BOT_TOKEN`                  | worker     | Optional — Telegram alert dispatcher. Falls back to log-and-drop when empty.           |
| `CORS_ORIGINS`                        | api        | Comma-separated allow-list for the dashboard origin. Defaults to `http://localhost:5173`. |
| `VITE_API_BASE_URL`                   | web        | API URL the dashboard hits. Baked into the web image at build time.                    |
| `VITE_KEYCLOAK_URL` / `_REALM` / `_CLIENT_ID` | web | OIDC config for the dashboard.                                                         |

Production deployments must override:

1. **Every secret** (`DATABASE_PASSWORD`, `R2_*`, `ARGUSLOG_INITIAL_ADMIN_PASSWORD`).
2. **Every URL** to point at your domain (`KEYCLOAK_ISSUER`, `VITE_API_BASE_URL`).
3. **`ARGUSLOG_PLATFORM_ADMINS`** — even a single-user instance still needs at
   least one admin to exist before tier grants work.

## First-boot bootstrap (Keycloak)

On first start, Keycloak imports the realm template from
`services/keycloak/realm/arguslog-realm.json` and creates the admin user
described by `ARGUSLOG_INITIAL_ADMIN_EMAIL` + `_PASSWORD`. The same email
also has to appear in `ARGUSLOG_PLATFORM_ADMINS` so the API guard sees it
as a platform admin once they log in.

After your first successful login, **immediately change the admin password**
in Keycloak. Re-importing the realm (via `--import-realm` on subsequent
boots) is a no-op once the realm exists, but if you ever wipe the Keycloak
data volume you'll be back to the bootstrap password.

## SMTP

The realm template ships with `mailhog:1025` as the default SMTP relay so
emails work out of the box in dev. For production, set SMTP via the
Keycloak admin API after first boot:

```bash
# Get an admin token then PUT the realm's smtpServer config — example
# uses curl + jq; substitute your KC issuer and admin creds.
TOKEN=$(curl -s -X POST $KEYCLOAK_ISSUER/protocol/openid-connect/token \
  -d "grant_type=password" -d "client_id=admin-cli" \
  -d "username=$ADMIN_EMAIL" -d "password=$ADMIN_PASSWORD" \
  | jq -r .access_token)

curl -X PUT $KEYCLOAK_ISSUER/admin/realms/arguslog \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"smtpServer":{"host":"smtp.example.com","port":"587",
       "from":"noreply@yourdomain.com","auth":"true",
       "user":"...","password":"...","starttls":"true"}}'
```

The SMTP settings live in the Keycloak DB, not the realm import file —
once configured they survive future realm re-imports.

## TLS / reverse proxy

The shipped compose file binds services on plain HTTP for local
development. For production, terminate TLS at a reverse proxy (Caddy,
nginx, Traefik) and point it at the unencrypted internal services. The
`CORS_ORIGINS` env on `api` must list your public dashboard origin
(`https://app.yourdomain.com`) so browser requests aren't blocked.

## Backups

Two things to back up regularly:

1. **Postgres** — `pg_dump arguslog | gzip > backup-$(date +%F).sql.gz`. The
   tables `users`, `organizations`, `org_members`, `issues`, `events`, plus
   `admin_audit_log` are the operationally important ones.
2. **Object store** — sourcemaps are large but recoverable; the events
   themselves carry all the symbolication metadata so a sourcemap loss
   degrades issues to minified frames but doesn't lose data.

Keycloak's own DB stores user identities + SMTP config — back it up the
same way you back up Postgres.

## Upgrades

Migrations are owned by `services/api` (Flyway-managed). On every release:

1. Pull the new image.
2. Stop the old container.
3. Start the new container — Flyway runs pending migrations automatically
   on api boot.
4. Verify with `SELECT version FROM flyway_schema_history ORDER BY installed_on DESC LIMIT 3;`

Worker + ingest don't run migrations; they can be upgraded in any order
relative to api as long as they stay within one major version.

## Tier model

There's no payment surface in the code. Every new user signup lands on the
tier set by `ARGUSLOG_DEFAULT_TIER`. To elevate a user:

- Sign in as a platform admin (your email must be in
  `ARGUSLOG_PLATFORM_ADMINS`).
- Open `/admin` in the dashboard.
- Find the user → click Grant → choose tier (silver / gold / platinum) and
  duration (0 = permanent, or 1 / 3 / 6 / 12 months).
- A daily 04:00 UTC cron downgrades expired grants back to `regular`. The
  schedule is configurable via `arguslog.tier.expiry-cron`.

For a single-tenant instance the simplest setup is
`ARGUSLOG_DEFAULT_TIER=platinum` — everyone is uncapped, no grants needed.

## CI

A minimal smoke test for the full stack lives in
`.github/workflows/self-host-smoke.yml`. It boots `docker-compose.full.yml`
on every PR and asserts each service exposes a healthy probe.

For your own deployment pipelines, the only contract is "Flyway must succeed
before the API container reports ready". A simple wrapper that waits for
`/actuator/health/readiness` to return `UP` is sufficient.

## Troubleshooting

- **`column "plan" does not exist`** — you upgraded directly from a v1.x
  install to v2.x without running the V29 backfill. Run `flyway migrate`
  manually before starting the new api container.
- **Web shows a blank page** — likely the API URL baked into the web image
  doesn't match where the API is actually running. Rebuild the web image
  with the right `VITE_API_BASE_URL` value.
- **Admin grants don't take effect** — the daily cron downgrades expired
  grants; if a user complains their tier dropped, check `users.tier_expires_at`
  vs `NOW()` in Postgres. A grant with `months=0` has
  `tier_expires_at IS NULL` and never expires.
- **CORS errors on the dashboard** — set `CORS_ORIGINS` on api to your
  exact dashboard origin (scheme + host + port, no trailing slash).
