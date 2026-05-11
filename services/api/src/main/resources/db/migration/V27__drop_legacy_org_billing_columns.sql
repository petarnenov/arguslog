-- Phase 4 of per-user billing — drop the legacy org-level billing identity columns.
--
-- V26 mirrored these onto users and backfilled them; Phase 2 reads + Phase 3 admin grants +
-- Phase 4 downgrade worker all switched to the user row as the source of truth. The dual-writes
-- kept the org columns in sync during the transition window so a botched migration could be
-- rolled forward easily; now that the system has been on user-primary writes for the test cycle
-- it's safe to drop the columns.
--
-- After this migration:
--   - cap-checks, admin reads, billing UI all read from users.*
--   - Stripe webhooks + admin grants write to users.* only
--   - the auto-downgrade worker iterates users.payment_grace_until
--   - the `organizations` table goes back to being just identity + slug + name + created_at

ALTER TABLE organizations
  DROP COLUMN plan,
  DROP COLUMN plan_renews_at,
  DROP COLUMN billing_interval,
  DROP COLUMN stripe_customer_id,
  DROP COLUMN bonus_until,
  DROP COLUMN bonus_reason,
  DROP COLUMN bonus_granted_by,
  DROP COLUMN bonus_granted_at,
  DROP COLUMN payment_grace_until;
