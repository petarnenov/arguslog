-- OSS conversion Phase 2 — rename users.plan → users.tier and the bonus_* columns into tier_*
-- shape, and drop the SaaS-specific billing columns that no longer have a code path.
--
-- After V29's data backfill, the column carries only regular/silver/gold/platinum values, so a
-- pure rename is safe. Downstream Java code is updated in the same Phase 2 commit to read
-- `users.tier` and to map admin grants to tier_* columns.
--
-- Dropped columns (no longer wired to any code path post-Phase-2):
--   - plan_renews_at        — was set by Stripe / LS / NOWPayments checkout flows
--   - billing_interval      — monthly|annual; pricing cadence, irrelevant without billing
--   - stripe_customer_id    — Stripe customer reference
--   - payment_grace_until   — set by hourly grace-open cron; replaced by tier_expires_at
--
-- Renamed columns (bonus_* → tier_*): admin tier grants become first-class instead of a
-- "bonus on top of paid plan" overlay. The grant itself IS the tier — no separate bonus.
--   - bonus_until      → tier_expires_at
--   - bonus_reason     → tier_reason
--   - bonus_granted_by → tier_granted_by
--   - bonus_granted_at → tier_granted_at

ALTER TABLE users RENAME COLUMN plan TO tier;
ALTER TABLE users RENAME COLUMN bonus_until      TO tier_expires_at;
ALTER TABLE users RENAME COLUMN bonus_reason     TO tier_reason;
ALTER TABLE users RENAME COLUMN bonus_granted_by TO tier_granted_by;
ALTER TABLE users RENAME COLUMN bonus_granted_at TO tier_granted_at;

ALTER TABLE users
  DROP COLUMN plan_renews_at,
  DROP COLUMN billing_interval,
  DROP COLUMN stripe_customer_id,
  DROP COLUMN payment_grace_until;
