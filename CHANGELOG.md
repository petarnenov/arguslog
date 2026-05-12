# Changelog

## 2.0.0 — Open-source release

The SaaS-only repository becomes a self-hostable OSS project. The hosted
instance at arguslog.org continues to run; others can now run the same
code on their own infrastructure.

### Breaking

- Removed all payment integrations: Stripe, NOWPayments, Lemon Squeezy,
  crypto checkout, customer portal, `plan_purchases` table. Every related
  controller, service, repository, and database table is deleted.
- Renamed the four user tiers: `free`→`regular`, `starter`→`silver`,
  `pro`→`gold`, `business`→`platinum`. `enterprise` folds into `platinum`
  on backfill. The wire string in `users.tier` carries the new spelling
  exclusively after V32.
- `GET /api/v1/me` now returns `{tier, tierExpiresAt, tierReason}` instead
  of `{plan, planRenewsAt, paymentGraceUntil, bonusUntil, bonusReason}`.
  Dashboard + SDKs that consume `/me` need the 2.0.0 line.
- `POST /api/v1/admin/orgs/{orgId}/grant` (per-org grant) removed. Use
  `POST /api/v1/admin/users/{userId}/grant` — the granted tier covers
  every org the user owns automatically.
- MCP server: dropped `list_billing_plans`, `grant_bonus_plan`,
  `get_org_usage`. Added `grant_user_tier` with `months=0` for permanent
  grants.

### Added

- `ARGUSLOG_DEFAULT_TIER` env var — controls the tier new signups land on.
  Defaults to `regular` (matches the hosted instance behavior); self-hosters
  who want everyone uncapped can set it to `platinum`.
- `TierExpiryJob` worker cron (daily 04:00 UTC, configurable via
  `arguslog.tier.expiry-cron`) that downgrades users whose `tier_expires_at`
  has elapsed back to `regular`.
- `SELF_HOSTING.md` operational runbook.
- `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`.

### Migrations

- V28: additive — adds `regular / silver / gold / platinum` to the
  `org_plan` enum without removing legacy values.
- V29: backfills `users.plan` from legacy names to color-themed names.
- V30: renames `users.plan` → `users.tier`, renames `bonus_*` columns to
  `tier_*`, drops `plan_renews_at`, `billing_interval`,
  `stripe_customer_id`, `payment_grace_until`.
- V31: drops `plan_purchases`, `stripe_events`, `crypto_invoices`,
  `crypto_events`, `renewal_reminders_sent` tables. Operator should
  `pg_dump` these via `scripts/archive-billing-tables.sh` before merging.
- V32: replaces the `org_plan` enum with `org_tier`
  (`regular / silver / gold / platinum` only).

### Removed

- Public mirror sync infrastructure (`scripts/sync-public-mirror.sh`,
  `scripts/public-mirror/`, `.github/workflows/sync-public-mirror.yml`).
  The single `petarnenov/arguslog` repo is now the canonical OSS home.
