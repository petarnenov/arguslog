# P4 — Billing + polish

> Goal (per project memory): "Billing + polish".
>
> Definition of done:
>
> - A user can self-serve upgrade Free → Pro via Stripe Checkout, see their
>   current plan + usage on a Billing page, and manage subscription via
>   Stripe Customer Portal.
> - Per-project burst rate-limit + per-org monthly event cap enforced at
>   the ingest tier via Bucket4j-on-Redis. Quota-exceeded events get 429 +
>   dashboard banner.
> - Stripe webhooks keep `organizations.plan` + `plan_renews_at` in sync.
> - PAT page on the dashboard so users can mint / list / revoke without
>   curl.

## Plans

| Plan | Price | Events / month | Projects | Retention |
| --- | --- | --- | --- | --- |
| Free | $0 | 5,000 | 1 | 30 days |
| Pro  | $9 / mo | 100,000 | 10 | 30 days |
| Enterprise | contract | custom | unlimited | 90–365 days |

Soft-cap behavior: when usage hits the cap, ingest returns 429 + dashboard
shows a "quota exceeded" banner. Counter resets at `period_start + 1 month`.
No auto-downgrade in P4 — `payment_failed` shows a grace-period banner;
hard downgrade is a P5 decision.

## Milestone tracker

| #   | Milestone                                                                                                   | Status  | Commit    |
| --- | ----------------------------------------------------------------------------------------------------------- | ------- | --------- |
| 1   | API: Plan catalog + `GET /api/v1/orgs/{id}/usage` for the BillingPage banner.                               | ✅ done | `6723bd4` |
| 2   | INGEST: `RealQuotaEnforcer` — Bucket4j in-memory burst + Postgres atomic UPSERT monthly cap.                | ✅ done | _pending_ |
| 3   | API: Stripe Checkout endpoint — `POST /api/v1/orgs/{id}/billing/checkout-session`.                          | ⏳ next | —         |
| 4   | API: Stripe Customer Portal endpoint — `POST /api/v1/orgs/{id}/billing/portal`.                             | pending | —         |
| 5   | API: Stripe webhook handler — `checkout.session.completed`, `subscription.{updated,deleted}`, `invoice.payment_failed`. | pending | —         |
| 6   | Web: BillingPage — current plan, usage vs cap, upgrade CTA → Checkout, manage → Portal.                     | pending | —         |
| 7   | Web: PersonalAccessTokensPage (P3 #7c carry-forward) — mint / list / revoke.                                | pending | —         |

## Architecture decisions to lock in

- **Plan catalog source of truth:** Java enum `PlanTier { FREE, PRO, ENTERPRISE }` with
  per-tier `events`, `projects`, `retentionDays`. The DB column stays the existing
  `org_plan` enum (mirror), but limits live in code so changes are atomic and reviewable.
  Stripe price IDs come from env (`STRIPE_PRICE_PRO`) so the same code can target test
  + prod with different price objects.
- **Quotas — Bucket4j on Redis:** the existing `QuotaEnforcer` port in ingest gets a
  `RedisQuotaEnforcer` impl. Two buckets per check:
    1. **Per-project burst** — small token bucket (e.g. 60 events / 10s) so a malicious
       SDK can't overload Redis Streams. Refills continuously.
    2. **Per-org monthly cap** — one consumed token = one event, refills only at the
       month boundary. Read backing `quotas.events_count` once per request via Bucket4j's
       distributed counter.
- **Stripe Checkout flow:** api creates a Checkout Session with `mode=subscription`,
  passes `client_reference_id = org_id`, `customer_email` from the requesting user,
  `success_url` + `cancel_url` back to the dashboard. Returns `{ url }` for the web to
  `window.location` to.
- **Webhook security:** verify `Stripe-Signature` header against `STRIPE_WEBHOOK_SECRET`
  using `Webhook.constructEvent`. Reject anything else with 400. Webhook endpoint stays
  `permitAll` (no JWT — Stripe POSTs anonymously).
- **Webhook idempotency:** Stripe redelivers on 5xx. Persist processed `event.id` in a
  small `stripe_events` table with PK = `event_id`; INSERT-IGNORE → skip if seen.
- **Customer Portal:** `BillingPortalSession.create` for the org's `stripe_customer_id`;
  `return_url` back to the dashboard's BillingPage.
- **Quota exceeded UX:** existing 429 in `EventIngestController` stays. Dashboard polls
  `GET /api/v1/orgs/{id}/usage` (new endpoint) every minute on the BillingPage; banner
  on every page when ratio ≥ 90%.
- **Retention:** event purge job is **out of scope for P4** — the retention column already
  exists on `organizations.retention_days_override`; a daily worker job that DELETEs
  rows older than the effective retention is P5.
- **PAT page:** Mantine table mirroring AlertDestinationsPage. Create modal shows the
  plaintext token once with a Copy button + warning that it won't be shown again.
  Revoke is a single confirm button. No rotate flow — revoke + recreate.

## Carry-forwards from P3

- One Flyway owner = api. New tables in this phase: `stripe_events` (idempotency),
  maybe a `usage_snapshots` if metering becomes a thing (deferred).
- All new web pages go through `RequireAuth` + the existing org-aware sidebar.
- Tests stay 75/40/10 — controller MockMvc + service unit + Testcontainers Postgres
  for repos. Stripe webhook is verified via static fixture payloads (signature mocked).

## Out of scope for P4 (revisit in P5)

- Auto-downgrade on `payment_failed` after grace period.
- Metered billing / usage-based pricing.
- Granular PAT scopes (`releases:write`, etc.) — single implicit `pat` scope holds.
- `AesGcmSecretCipher` extraction to a shared module.
- RLS owner-bypass test split (split container roles).
- Daily retention purge worker job.
- Email receipts on successful checkout (Stripe sends its own).
- Annual prepay / yearly discount.
