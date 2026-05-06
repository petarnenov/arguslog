# P5 — Launch readiness

> Goal (per project memory): "Launch readiness — k6, dogfood, DNS cutover".
>
> Definition of done:
>
> - Retention promise (Free/Pro 30d, Enterprise per-org override) is enforced
>   by a worker job — no manual cleanup needed.
> - A customer whose card stops working keeps Pro for 7 days, sees a banner,
>   and is auto-downgraded to FREE if no successful payment within the grace
>   period.
> - `sdk-browser` + `sdk-react` are on npm, `java-sdk` is on Maven Central, all
>   under semver-tagged GitHub releases.
> - Argus monitors itself: api/ingest/worker emit events via the published
>   `java-sdk`, web emits via the published `sdk-browser` + `sdk-react`.
> - k6 baseline numbers (p95, p99, error rate at target RPS) recorded for the
>   ingest hot path + api login flow against staging.
> - Production lives at `arguslog.org` (via Cloudflare → Railway), TLS valid,
>   subdomains carved out for app/api/ingest/auth.

## Locked decisions (P5 planning Q&A)

| Decision | Choice | Why |
| --- | --- | --- |
| Scope | Launch-focused | Product is feature-complete after P4; without launch infra there are no real users to inform feature work. |
| Launch timing | Within P5, DNS cutover last | Real users get access at end of P5. |
| Domain | `arguslog.org` (Cloudflare) | Already registered + DNS-managed there. |
| Retention purge | Hybrid: TimescaleDB chunk policy @365d + per-org DELETE for Free/Pro @30d | Hypertable chunk drop is O(1); per-org DELETE handles the 30d common case without losing Enterprise overrides. |
| Payment grace | 7 days → auto-downgrade | Industry standard; balances false-positive risk vs disk-burn risk. |
| SDK distribution | Published npm + Maven Central | Dogfood exercises the real install path; surfaces packaging bugs. |
| Railway envs | Staging + Production | Staging is the deploy target for k6 + dogfood; production turns on at DNS cutover. |

## Plans (unchanged from P4 — for reference)

| Plan | Price | Events / month | Projects | Retention |
| --- | --- | --- | --- | --- |
| Free | $0 | 5,000 | 1 | 30 days |
| Pro  | $9 / mo | 100,000 | 10 | 30 days |
| Enterprise | contract | custom | unlimited | 90–365 days |

## Milestone tracker

| #   | Milestone                                                                                                   | Status   | Commit |
| --- | ----------------------------------------------------------------------------------------------------------- | -------- | ------ |
| 1   | WORKER: Retention purge job — TimescaleDB chunk policy @365d + nightly per-org DELETE for orgs with effective retention < 365d. | ✅ done | `027fa23` |
| 2   | API + WORKER: Payment failure grace period — `invoice.payment_failed` sets `payment_grace_until = NOW()+7d`; nightly job auto-downgrades expired grace; `invoice.payment_succeeded` clears it; BillingPage banner. | ⏳ next | —      |
| 3   | SDKs: GitHub Actions release workflow — `sdk-browser-v*` / `sdk-react-v*` → npm publish, `java-sdk-v*` → Maven Central via OSSRH (signed). Tag-driven. | pending  | —      |
| 4   | INFRA: Railway staging environment — services (api/ingest/worker/web) + managed Postgres+Timescale + Redis + R2 bucket + Keycloak. Secrets wiring + first deploy from `main`. | pending  | —      |
| 5   | INFRA: k6 load testing — ingest hot path (events POST), api login + dashboard read, recorded baseline (p95/p99/err-rate at target RPS) against staging. | pending  | —      |
| 6   | DOGFOOD: api/ingest/worker import published `java-sdk`, web imports `@arguslog/sdk-browser` + `@arguslog/sdk-react`. Dedicated `arguslog-internal` org with one project per service. | pending  | —      |
| 7   | INFRA: Railway production deploy + DNS cutover — promote staging build to production env, point `arguslog.org` subdomains at Railway, TLS via Railway, Cloudflare proxy decision per subdomain. | pending  | —      |

## Architecture decisions to lock in

- **Retention purge — hybrid two-tier:**
    1. TimescaleDB native retention policy on the `events` hypertable at
       365 days — handles Enterprise tier automatically, O(1) chunk drop.
    2. Spring `@Scheduled` job in the worker (daily 03:00 UTC). For each
       org with `effective_retention_days < 365`, batched
       `DELETE FROM events WHERE org_id = ? AND received_at < ? LIMIT 10000`
       in a loop until no rows. Lock pressure stays bounded by the LIMIT.
    3. First deploy ships with `arguslog.retention.dry-run=true` — logs what
       would be deleted instead of deleting. Flip to false after one cycle.
- **Payment grace period — schema + flow:**
    1. New column `organizations.payment_grace_until TIMESTAMPTZ NULL` (Flyway
       V9). NULL = no grace active.
    2. `StripeWebhookService` extends `invoice.payment_failed` to set
       `payment_grace_until = NOW() + INTERVAL '7 days'` (idempotent — only
       set if currently NULL or in past).
    3. New event type handled: `invoice.payment_succeeded` → clears
       `payment_grace_until`.
    4. New nightly Spring `@Scheduled` job in worker: `WHERE
       payment_grace_until < NOW() AND plan = 'pro'` → set plan = FREE,
       clear grace, emit audit log entry.
    5. `UsageResponse` exposes `paymentGraceUntil` for the dashboard;
       BillingPage shows red banner + "Update payment method" CTA → Portal.
- **SDK publishing pipeline:**
    1. Conventional commit + tag-driven: `git tag sdk-browser-v0.1.0 && git
       push --tags` triggers `.github/workflows/release-sdk-browser.yml`.
    2. JS workflow: pnpm build → version verify → `npm publish --access
       public` with `NODE_AUTH_TOKEN` from secrets; mirror tag to GitHub
       release with autogenerated notes.
    3. Java workflow: Gradle `publishToMavenCentral` via the OSSRH plugin,
       GPG-signed with `MAVEN_GPG_PRIVATE_KEY`. Stages → auto-release on green.
    4. All workflows run from a clean checkout; never publish from a dirty
       working tree.
- **Railway environment topology:**
    1. One Railway project, two environments: `staging`, `production`.
       Variables namespaced per env.
    2. Per-env services: `api`, `ingest`, `worker`, `web` (Vite static build
       served via Caddy), `keycloak`, plus managed Postgres+Timescale and
       Redis. R2 bucket lives in Cloudflare not Railway.
    3. GitHub Actions deploy workflow: push to `main` → staging deploy via
       Railway CLI; production deploy is a manual workflow_dispatch with
       confirmation input.
    4. Secrets injected via Railway's env panel — no plaintext in the repo.
       Stripe keys, Keycloak admin creds, R2 credentials, JWT signing key.
- **Dogfood SDK install:**
    1. Dedicated `arguslog-internal` org created by hand on first prod
       boot, with three projects: `argus-api`, `argus-ingest`,
       `argus-worker`, `argus-web`.
    2. Each service gets a DSN env var (`ARGUS_DSN`) — when unset (e.g.
       local dev), the SDK no-ops. When set, errors flow through the same
       ingest path real customers use.
    3. Web emits both unhandled errors (sdk-browser default) and
       boundary-caught React errors (sdk-react `<ErrorBoundary>`).
    4. Java services emit via Logback appender from `java-sdk` so existing
       SLF4J calls become events without code changes.
- **k6 scenarios:**
    1. `infra/k6/ingest-hot-path.js` — POST events at ramp-up RPS until 5%
       error rate or p99 > 1s. Baseline target: 500 RPS sustained at p99
       < 250ms (free-tier Railway sizing).
    2. `infra/k6/dashboard-read.js` — login + list issues + open detail at
       50 VU. Baseline target: p95 < 400ms.
    3. Numbers recorded in `docs/p5-baseline.md`. Future regressions in P6+
       PRs measured against this.
- **DNS plan:**
    1. Cloudflare DNS zone for `arguslog.org` (already there).
    2. Subdomains: `app.arguslog.org` (web), `api.arguslog.org` (api),
       `ingest.arguslog.org` (ingest), `auth.arguslog.org` (keycloak).
       Apex either redirects to `app.` or hosts a marketing page (out of
       scope for P5 — TBD).
    3. Cloudflare proxy ON for `app.` + `api.` + `auth.` (WAF / DDoS
       benefit). Cloudflare proxy OFF for `ingest.` — every event POST
       would otherwise pay a double-hop, and Cloudflare's free WAF doesn't
       help an authenticated event-write endpoint.
    4. Railway "Custom Domain" feature attaches each subdomain to its
       service; TLS provisioned automatically via Let's Encrypt.

## Out of scope for P5 (revisit in P6)

- Granular PAT scopes (`releases:write`, etc.). Single implicit scope holds.
- `AesGcmSecretCipher` extraction to a shared module.
- RLS owner-bypass test split (split container roles).
- Test mock churn → `@TestConfiguration` extraction. Painful but every-PR overhead.
- Annual prepay / yearly discount.
- Metered billing / usage-based pricing.
- Marketing site at apex `arguslog.org`.
- SOC2-style audit log export.
- Backup + DR rehearsal (Railway has snapshots; full DR drill is P6).
- Status page (`status.arguslog.org`) — Better Stack already on the list.

## Test strategy carry-forward

- All new server work keeps the 75/40/10 unit/integration/e2e split.
- k6 scripts live under `infra/k6/` with a README explaining how to run
  them locally + against staging. They are NOT part of `pnpm test` — too
  slow, too noisy.
- Dogfood errors should NOT break tests — DSN env var stays unset in CI,
  so the SDK no-ops.
