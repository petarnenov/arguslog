# Post-launch backlog

State after P5 cutover (2026-05-07): Arguslog is live on `arguslog.org` with all four custom
domains answering 200, dogfood emit-side active, email verification working through Resend.
This file tracks everything that was deliberately deferred plus things that surfaced during the
cutover week.

Reconciled against the codebase on 2026-05-15: items marked ~~strike~~ are confirmed shipped;
items marked _(obsolete — OSS)_ were dropped by the OSS conversion (payments/Stripe removed).

## Done (security hardening after launch)

- [x] Cloudflare API token revoked (the one shared during DNS cutover).
- [x] Keycloak `admin` master-realm password rotated.
- [x] Resend API key + R2 access key rotated.
- [x] Per-IP / per-JWT rate limit on `/api/**` (Bucket4j + Caffeine LRU): 600/min DEFAULT,
      30/min STRICT for `/api/v1/webhooks/**`. Verified live: 100 parallel webhook hits
      → 30 succeed, 70 return 429.
- [x] Keycloak realm-level brute-force protection enabled (5 failures → 60s wait increment,
      15min max wait, 12h failure reset).

## Open — feature work

| #   | Item                                                            | Trigger                                |
| --- | --------------------------------------------------------------- | -------------------------------------- |
| 6   | Email-verification end-to-end smoke from real registration flow. | Before second real user registers.     |
| 8   | Audit log CSV/JSON export endpoint + real prod DR drill.        | SOC2 prep / first enterprise customer. |

For item 8 the MinIO-backed restore smoke (`scripts/restore-smoke.sh` + weekly CI) is shipped;
what remains is a dump endpoint on `/api/v1/admin/audit` and a rehearsal restore from a real
production backup.

### Shipped (was listed as open)

- ~~2~~ First SDK publish (`@arguslog/sdk-browser@0.1.1`, `@arguslog/sdk-react@0.1.1`,
  `org.arguslog:java-sdk:0.1.0`) — done 2026-05-07.
- ~~3~~ `NPM_TOKEN` + Maven Central creds + GPG key as repo secrets — done 2026-05-07.
- ~~4~~ Marketing / landing page on apex — `apps/landing/` + Railway service
  `arguslog-landing`; apex CNAME points at it.
- ~~5~~ Status page — self-hosted `/status` in landing (`apps/landing/src/pages/StatusPage.tsx`)
  probes api/ingest/web/auth/mcp + markdown incidents feed.

### Obsolete after OSS conversion

- ~~1~~ Stripe live keys — payments removed.
- ~~7~~ `payment_failed` auto-downgrade rehearsal — replaced by `TierExpiryJob`
  (daily 04:00 UTC); no payment webhook path exists anymore.

## Open — tech debt (carry-forward from P4/P5 "out of scope")

| #   | Item                                                                                            |
| --- | ----------------------------------------------------------------------------------------------- |
| 1   | `@TestConfiguration` extraction to stop the mock churn that hits every controller-test commit. |

214 inline `@MockitoBean` declarations across api/worker/ingest tests still re-declare the same
bean stubs every commit.

### Shipped (was listed as open)

- ~~2~~ `AesGcmSecretCipher` extraction — `lib/crypto-aes-gcm/` ships `SecretCipher` +
  `AesGcmSecretCipher`; api + worker consume the shared lib.
- ~~3~~ RLS owner-bypass test split — `RowLevelSecurityIsolationTest` builds a `NOBYPASSRLS`
  `app_role` and runs every policy through it; the owner connection only seeds.
- ~~4~~ Granular PAT scopes — `PatScope` enum + `PatScopeGuard.require(...)` on
  `ReleaseController` + `SourceMapArtifactController` (`releases:write`, `sourcemaps:write`).
- ~~7~~ `import/order` lint warning in `apps/web/src/providers.tsx` — `pnpm exec eslint
  src/providers.tsx` exits clean.

### Obsolete after OSS conversion

- ~~5~~ Annual prepay / yearly discount — Stripe removed.
- ~~6~~ Metered billing / usage-based pricing — paid plans removed; tier model is admin-grant-driven.

## Open — operational

| #   | Item                                                                                                                  |
| --- | --------------------------------------------------------------------------------------------------------------------- |
| 1   | Re-enable `RETENTION_DRY_RUN=false` after one nightly cycle confirms the per-org delete count is sane.                |
| 2   | Move staging Keycloak's backing store off the auto-provisioned `Postgres` template onto a long-lived volume.          |
| 4   | Decide if production should use a separate R2 bucket from staging (currently `arguslog-attachments` serves both).     |

Code anchors: item 1 — `services/worker/src/main/resources/application.yml:47` still defaults
to `true`. Item 2 — `services/keycloak/railway.toml` has no volume declaration; backing is the
managed Postgres. Item 4 — `infra/railway/README.md:156` + `:183` both pin
`R2_BUCKET=arguslog-attachments`.

### Shipped (was listed as open)

- ~~5~~ `RAILWAY_TOKEN_PRODUCTION` wired into the deploy workflow —
  `.github/workflows/deploy.yml:70,88,93` ternaries on `inputs.environment == 'production'`.

### Obsolete after OSS conversion

- ~~3~~ `arguslog-internal` `enterprise` plan decision — plans → tiers; runs on `platinum`.

## Open — Slack polish (deferred from `docs/slack-plan.md`)

| #   | Item                                                                                                        |
| --- | ----------------------------------------------------------------------------------------------------------- |
| S1  | `/arguslog ping` subcommand — needs a Java synthetic-event builder + HTTP ingest client.                    |
| S2  | `app_uninstalled` Slack Events API handler — auto-deactivate when a workspace removes the app Slack-side.   |

`SlackCommandDispatcher` javadoc already documents the ping deferral; operators can use the
dashboard Connect wizard's Test ping button in the meantime. `SlackWorkspaceWriteRepository
.deactivate()` exists but is only called from the dashboard disconnect flow.

## Worth knowing

- **Realm seed file** (`services/keycloak/realm/arguslog-realm.json`) still references the
  docker-compose `mailhog` SMTP host. Production overrides via admin API are NOT in the file.
  Re-applying the realm import on a clean DB will regress SMTP back to mailhog — patch via
  `PUT /admin/realms/arguslog` with the Resend SMTP block before users register.
- **Cloudflare zone SSL/TLS mode = Full** is required by Railway's custom-domain TLS. Don't
  let anyone bump it to "Flexible" — origin handshakes break.
- **Cloudflare proxy** is ON for `app/api/auth.arguslog.org` and OFF for `ingest.arguslog.org`
  (proxy off avoids a double-hop on every event POST). Keep it that way.
