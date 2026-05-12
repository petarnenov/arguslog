-- OSS conversion Phase 1 — additive enum extension.
--
-- The hosted SaaS billing model is being replaced by a tier-grant model: platform admins
-- hand out silver/gold/platinum tiers for free instead of selling them via Stripe / LS /
-- NOWPayments. Tier names change to be color-themed: regular / silver / gold / platinum
-- (enterprise folds into platinum — no production rows are on it).
--
-- Phase 1 is intentionally backward compatible: we ADD the new enum values without removing
-- the old ones, so running JVMs that read `free|starter|pro|business|enterprise` keep
-- working while a forward-compatible PlanTier shim teaches Java to accept both spellings.
-- The destructive cutover (backfill, rename column plan→tier, drop billing columns/tables,
-- drop legacy enum values) ships in Phase 2 together with the deletion of billing code paths
-- that still reference those columns.
--
-- ALTER TYPE … ADD VALUE is committed atomically in its own statement; we use IF NOT EXISTS
-- so re-running the migration on an already-patched DB is a no-op. Same pattern as V23 which
-- introduced starter/business.

ALTER TYPE org_plan ADD VALUE IF NOT EXISTS 'regular';
ALTER TYPE org_plan ADD VALUE IF NOT EXISTS 'silver';
ALTER TYPE org_plan ADD VALUE IF NOT EXISTS 'gold';
ALTER TYPE org_plan ADD VALUE IF NOT EXISTS 'platinum';
