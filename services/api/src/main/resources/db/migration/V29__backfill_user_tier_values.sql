-- OSS conversion Phase 2 — backfill users.plan from legacy names to color-themed names.
--
-- V28 added regular/silver/gold/platinum as valid enum values without disturbing the existing
-- free/starter/pro/business/enterprise rows. This migration rewrites every row to the new
-- spelling so V30+ can rename the column to `tier` and V32 can drop the legacy enum entries.
--
-- Mapping mirrors PlanTier.aliasOrNull(): free→regular, starter→silver, pro→gold,
-- business→platinum. Enterprise grandfathers up to platinum — same uncapped allowance
-- semantics, no production rows degraded.

UPDATE users
   SET plan = CASE plan
                WHEN 'free'       THEN 'regular'::org_plan
                WHEN 'starter'    THEN 'silver'::org_plan
                WHEN 'pro'        THEN 'gold'::org_plan
                WHEN 'business'   THEN 'platinum'::org_plan
                WHEN 'enterprise' THEN 'platinum'::org_plan
                ELSE plan
              END
 WHERE plan IN ('free', 'starter', 'pro', 'business', 'enterprise');

-- Sanity: no row should be on a legacy value after this.
DO $$
DECLARE
  legacy_count INTEGER;
BEGIN
  SELECT COUNT(*) INTO legacy_count
    FROM users
   WHERE plan IN ('free', 'starter', 'pro', 'business', 'enterprise');
  IF legacy_count > 0 THEN
    RAISE EXCEPTION 'V29 backfill incomplete: % users still on legacy plan values', legacy_count;
  END IF;
END $$;
