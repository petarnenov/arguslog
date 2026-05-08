# Post-launch backlog

State after P5 cutover (2026-05-07): Arguslog is live on `arguslog.org` with all four custom
domains answering 200, dogfood emit-side active, email verification working through Resend.
This file tracks everything that was deliberately deferred plus things that surfaced during the
cutover week.

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

| #     | Item                                                                                                                                                    | Trigger                                |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------- |
| 1     | Stripe live keys (`STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_PRO`) on prod api.                                                           | Ready to charge real cards.            |
| ~~2~~ | ~~First SDK publish~~ — `@arguslog/sdk-browser@0.1.1`, `@arguslog/sdk-react@0.1.1`, `org.arguslog:java-sdk:0.1.0` are live (Maven Central sync ~hours). | Done 2026-05-07.                       |
| ~~3~~ | ~~NPM_TOKEN + Maven Central creds + GPG key as repo secrets~~ — wired and rotated through the first publish.                                            | Done 2026-05-07.                       |
| 4     | Marketing / landing page on apex `arguslog.org` (currently 404).                                                                                        | Public launch.                         |
| 5     | Status page (Better Stack or self-hosted).                                                                                                              | First customer asks "is it down?".     |
| 6     | Email-verification end-to-end smoke from real registration flow.                                                                                        | Before second real user registers.     |
| 7     | Auto-downgrade rotation rehearsal — fire a `payment_failed` webhook + watch worker downgrade.                                                           | Before first Pro customer.             |
| 8     | Audit log export + backup/DR rehearsal.                                                                                                                 | SOC2 prep / first enterprise customer. |

## Open — tech debt (carry-forward from P4/P5 "out of scope")

| #   | Item                                                                                            |
| --- | ----------------------------------------------------------------------------------------------- |
| 1   | `@TestConfiguration` extraction to stop the mock churn that hits every controller-test commit.  |
| 2   | `AesGcmSecretCipher` extraction to a shared module.                                             |
| 3   | RLS owner-bypass test split (separate Testcontainers role with bypass off).                     |
| 4   | Granular PAT scopes (`releases:write`, `events:read`, etc.) instead of a single implicit scope. |
| 5   | Annual prepay / yearly discount on Stripe.                                                      |
| 6   | Metered billing / usage-based pricing variant.                                                  |
| 7   | Pre-existing `import/order` lint warning in `apps/web/src/providers.tsx`.                       |

## Open — operational

| #   | Item                                                                                                                  |
| --- | --------------------------------------------------------------------------------------------------------------------- |
| 1   | Re-enable `RETENTION_DRY_RUN=false` after one nightly cycle confirms the per-org delete count is sane.                |
| 2   | Move staging Keycloak's backing store off the auto-provisioned `Postgres` template onto a long-lived volume.          |
| 3   | Decide whether `arguslog-internal` dogfood org should keep `enterprise` plan in production (currently does).          |
| 4   | Decide if production should use a separate R2 bucket from staging (currently `arguslog-attachments` serves both).     |
| 5   | Wire `RAILWAY_TOKEN_PRODUCTION` into the deploy workflow's manual `workflow_dispatch` step (token exists, not wired). |

## Worth knowing

- **Realm seed file** (`services/keycloak/realm/arguslog-realm.json`) still references the
  docker-compose `mailhog` SMTP host. Production overrides via admin API are NOT in the file.
  Re-applying the realm import on a clean DB will regress SMTP back to mailhog — patch via
  `PUT /admin/realms/arguslog` with the Resend SMTP block before users register.
- **Cloudflare zone SSL/TLS mode = Full** is required by Railway's custom-domain TLS. Don't
  let anyone bump it to "Flexible" — origin handshakes break.
- **Cloudflare proxy** is ON for `app/api/auth.arguslog.org` and OFF for `ingest.arguslog.org`
  (proxy off avoids a double-hop on every event POST). Keep it that way.
