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
| `ARGUSLOG_WEB_API_BASE_URL`           | web        | API URL the dashboard hits. **Runtime** — entrypoint writes `/srv/runtime-config.js` at boot, no rebuild needed. |
| `ARGUSLOG_WEB_INGEST_BASE_URL`        | web        | Ingest URL for the Connect wizard's synthetic test-event probe. Runtime.               |
| `ARGUSLOG_WEB_KEYCLOAK_URL` / `_REALM` / `_CLIENT_ID` | web | OIDC config. Runtime.                                                                  |
| `ARGUSLOG_WEB_DOGFOOD_DSN`            | web        | Optional — DSN the dashboard's own errors get reported to. Runtime.                    |
| `ARGUSLOG_WEB_RELEASE`                | web        | Optional release stamp shown in event payloads. Runtime.                               |
| `VITE_*` (legacy)                     | web        | Build-time fallback, used when an image is baked with hardcoded URLs. The runtime path above supersedes these on every restart. |
| `SLACK_SIGNING_SECRET`                | api        | HMAC key Slack signs every slash-command POST with. Empty → `/api/v1/slack/commands` rejects everything (fail-closed).             |
| `SLACK_CLIENT_ID` / `SLACK_CLIENT_SECRET` | api    | OAuth app credentials (see "Slack workspace integration" below). Empty → install endpoint fail-closes to 503.                       |
| `SLACK_OAUTH_STATE_SECRET`            | api        | HMAC key for the install-flow state token. **Must be distinct from `SLACK_SIGNING_SECRET`.** Generate with `openssl rand -hex 32`. |
| `SLACK_OAUTH_REDIRECT_URI`            | api        | Public URL of the OAuth callback. Defaults to `http://localhost:8081/api/v1/slack/oauth/callback` for dev — set to your prod URL.   |

Production deployments must override:

1. **Every secret** (`DATABASE_PASSWORD`, `R2_*`, `ARGUSLOG_INITIAL_ADMIN_PASSWORD`).
2. **Every URL** to point at your domain — set `ARGUSLOG_WEB_API_BASE_URL`,
   `ARGUSLOG_WEB_KEYCLOAK_URL`, etc. on the web container; `KEYCLOAK_ISSUER`,
   `R2_ENDPOINT`, etc. on the api/worker containers.
3. **`ARGUSLOG_PLATFORM_ADMINS`** — even a single-user instance still needs at
   least one admin to exist before tier grants work.

### Web container env-var precedence

The dashboard reads its config in three stages, first-non-empty wins:

1. `window.__ARGUSLOG_CONFIG__` — injected by `/srv/runtime-config.js` which the
   container entrypoint regenerates from `ARGUSLOG_WEB_*` env vars on every start.
   Change a URL, restart the container, done.
2. `import.meta.env.VITE_*` — baked at build time. Used by the public arguslog.org
   image; self-hosters can ignore unless rebuilding from source.
3. Localhost-follows-hostname dev defaults (`make dev` paths).

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

### Dev-only `arguslog-seed` client — delete for production

The realm template ships a third OIDC client `arguslog-seed` (public,
Direct Access Grants enabled). It exists only to let `make seed` mint a
local demo user via password grant without going through the browser flow.

For self-hosted production, **delete this client after first boot**:

```bash
# Keycloak admin console → arguslog realm → Clients → arguslog-seed → Delete
# Or via kcadm.sh inside the running container:
docker exec arguslog-keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8180 --realm master --user "$ARGUSLOG_INITIAL_ADMIN_EMAIL" --password "$ARGUSLOG_INITIAL_ADMIN_PASSWORD"
docker exec arguslog-keycloak /opt/keycloak/bin/kcadm.sh delete clients/$(\
  docker exec arguslog-keycloak /opt/keycloak/bin/kcadm.sh get clients -r arguslog -q clientId=arguslog-seed --fields id --format csv --noquotes | tail -n1) \
  -r arguslog
```

The main browser-flow client `arguslog-web` keeps DAG **off**, which is the
production-safe posture. The `arguslog-api` confidential client is unaffected.

## Social login (GitHub / Google / GitLab)

The realm template ships with three identity providers defined — `github`,
`google`, and `gitlab`. Each is stripped from the imported realm if its
OAuth credentials are blank, so a vanilla self-host boots with
email/password only. To enable any of them, register an OAuth app upstream
and set the corresponding two env vars on the `arguslog-keycloak` container.

### Register the OAuth apps

| Provider | Where | Notes |
| --- | --- | --- |
| GitHub | <https://github.com/settings/developers> → **New OAuth App** | One callback URL per app — register a **separate OAuth App per environment** if you run multiple (local + staging + prod). Set Homepage URL = your dashboard origin; Authorization callback URL = the row from the table below. |
| Google | <https://console.cloud.google.com/apis/credentials> → **Create Credentials → OAuth client ID** (Web application) | Supports multiple Authorized redirect URIs — **one app covers every environment**. |
| GitLab | <https://gitlab.com/-/user_settings/applications> → **Add new application** | Confidential = yes; scopes = `openid`, `email`, `profile`, `read_user`. Supports multiple Redirect URIs — **one app covers every environment**. |

### Callback URLs

Substitute your environment's Keycloak hostname; the path is identical
across providers:

| Environment | URL |
| --- | --- |
| Local dev | `http://localhost:8180/realms/arguslog/broker/{github\|google\|gitlab}/endpoint` |
| Staging | `https://arguslog-keycloak-staging.up.railway.app/realms/arguslog/broker/{github\|google\|gitlab}/endpoint` |
| Production | `https://auth.arguslog.org/realms/arguslog/broker/{github\|google\|gitlab}/endpoint` |

### Env vars on the Keycloak container

```
GITHUB_CLIENT_ID
GITHUB_CLIENT_SECRET
GOOGLE_CLIENT_ID
GOOGLE_CLIENT_SECRET
GITLAB_CLIENT_ID
GITLAB_CLIENT_SECRET
```

For local dev, drop these into `.env.local` (gitignored) and re-run
`make fresh && make` — the render script substitutes them into the realm
file before Keycloak's first-boot import.

For staging / production, set them on the `arguslog-keycloak` service in
Railway. A post-boot patcher (`services/keycloak/configure-idps.sh`,
wired into the Dockerfile entrypoint) reads the env vars and `PUT`s the
matching IdP via the Admin API. The realm template stays free of any
real secrets — the patcher is idempotent and re-runs on every container
start.

### Auto-link by email

All three providers are configured with `trustEmail: true` and a custom
`auto-link` first-broker-login flow (`idp-create-user-if-unique`
ALTERNATIVE `idp-auto-link`). When a GitHub/Google/GitLab user logs in
for the first time with an email that already has a password account,
Keycloak silently links the two — no consent screen, no duplicate user.
The api side (`JwtUserSyncInterceptor`) is email-first, so it converges
on the same dashboard user regardless of which IdP minted the JWT.

> **Security note**: `trustEmail` accepts the IdP's `email_verified`
> claim at face value. GitHub, Google, and GitLab all verify ownership
> before setting that flag, so this is safe for these three providers.
> Be careful adding generic OIDC / SAML providers with the same
> `trustEmail: true` setting — an IdP that lies could take over an
> existing email-keyed account.

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

## Slack workspace integration

Inbound Slack — slash commands (`/arguslog …`) + a Connect-Slack button in
the dashboard — is optional. Leave the env vars unset and the install endpoint
fail-closes to 503 ("Slack OAuth is not configured"); the rest of the app is
unaffected.

To enable it:

1. **Create a Slack app** at https://api.slack.com/apps → "From scratch" →
   pick a workspace. Under **OAuth & Permissions**:
   - Add bot scopes: `commands`, `chat:write`, `incoming-webhook`.
   - Add a redirect URL matching `SLACK_OAUTH_REDIRECT_URI` (e.g.
     `https://api.yourdomain.com/api/v1/slack/oauth/callback`).
2. Under **Slash Commands**, add `/arguslog` pointing at
   `https://api.yourdomain.com/api/v1/slack/commands`.
3. Under **Basic Information** copy the **Signing Secret** → set as
   `SLACK_SIGNING_SECRET` on `arguslog-api`.
4. Under **OAuth & Permissions** copy the **Client ID** and **Client
   Secret** → set as `SLACK_CLIENT_ID` and `SLACK_CLIENT_SECRET`.
5. Generate a separate `SLACK_OAUTH_STATE_SECRET` (`openssl rand -hex 32`) —
   never reuse the signing secret; leaking one must not let an attacker
   forge the other.
6. Restart `arguslog-api`.

Users then go to **Dashboard → Settings → Integrations → Slack →
Connect Slack** and pick a default project. Slash commands work the moment
the OAuth flow completes.

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
