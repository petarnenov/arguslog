-- 4-tier pricing rollout: split the single PRO offering into Starter / Pro / Business so
-- customers can self-select the limits matching their volume. Free + Enterprise stay where they
-- are. The {@code org_plan} enum gets two new values; existing 'free', 'pro', 'enterprise' rows
-- keep working — Starter is a strictly lower tier than Pro, so no auto-migration of existing
-- Pro orgs is needed (they keep their higher caps).
--
-- Postgres ALTER TYPE ADD VALUE is committed atomically; we don't insert any rows referencing
-- the new values in this migration so the "cannot use new enum value in same transaction"
-- restriction doesn't bite.

ALTER TYPE org_plan ADD VALUE IF NOT EXISTS 'starter';
ALTER TYPE org_plan ADD VALUE IF NOT EXISTS 'business';
