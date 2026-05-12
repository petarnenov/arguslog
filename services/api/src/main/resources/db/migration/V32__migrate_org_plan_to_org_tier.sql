-- OSS conversion Phase 2 — replace the org_plan enum with org_tier, dropping the legacy
-- free/starter/pro/business/enterprise values.
--
-- Postgres' ALTER TYPE … DROP VALUE is unsupported, so we follow the canonical
-- "new type + recast column + drop old type" dance:
--   1. Create the new enum (org_tier) with only the four color-themed values.
--   2. ALTER COLUMN users.tier TYPE org_tier USING tier::text::org_tier — V29 guaranteed
--      every row is already on a value present in the new enum, so the cast is total.
--   3. Restore the NOT NULL + DEFAULT constraints (ALTER COLUMN … TYPE drops the default).
--   4. Drop the legacy org_plan type.
--
-- After this migration, lib/plan-tier/PlanTier.java is free to rename its Java constants
-- to REGULAR/SILVER/GOLD/PLATINUM and remove the fromDbValue alias shim — there are no more
-- legacy spellings to translate.

CREATE TYPE org_tier AS ENUM ('regular', 'silver', 'gold', 'platinum');

ALTER TABLE users
  ALTER COLUMN tier DROP DEFAULT,
  ALTER COLUMN tier TYPE org_tier USING tier::text::org_tier,
  ALTER COLUMN tier SET DEFAULT 'regular'::org_tier,
  ALTER COLUMN tier SET NOT NULL;

DROP TYPE org_plan;
