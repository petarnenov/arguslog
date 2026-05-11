-- Move billing identity from organizations onto users.
--
-- Prior model: each org carried its own plan/renew/bonus/grace/stripe_customer. After a platform
-- admin granted STARTER to one of a user's orgs, their *other* orgs still showed FREE because
-- the plan column was per-org. Product intent was always "user pays once, all their orgs are
-- covered" (GitHub-Pro-style), so we move billing identity onto the users row.
--
-- Phase 1 is additive: we MIRROR the columns on users and backfill from owned orgs without
-- dropping anything on organizations. Reads in Phase 2 start consulting users; Phase 4 (separate
-- migration, after monitoring) drops the org-level columns.

ALTER TABLE users
  ADD COLUMN plan                 org_plan          NOT NULL DEFAULT 'free',
  ADD COLUMN plan_renews_at       TIMESTAMPTZ,
  ADD COLUMN billing_interval     billing_interval_t NOT NULL DEFAULT 'monthly',
  ADD COLUMN stripe_customer_id   TEXT,
  ADD COLUMN bonus_until          TIMESTAMPTZ,
  ADD COLUMN bonus_reason         TEXT,
  ADD COLUMN bonus_granted_by     UUID REFERENCES users(id) ON DELETE SET NULL ON UPDATE CASCADE,
  ADD COLUMN bonus_granted_at     TIMESTAMPTZ,
  ADD COLUMN payment_grace_until  TIMESTAMPTZ;

-- Plain Stripe customer lookup — webhook handler resolves user from event.customer.
CREATE UNIQUE INDEX users_stripe_customer_id_uq
  ON users(stripe_customer_id)
  WHERE stripe_customer_id IS NOT NULL;

-- Backfill: for each user, find the "best" org they own and copy its billing identity onto the
-- user. "Best" = highest plan tier (FREE < STARTER < PRO < BUSINESS < ENTERPRISE), ties broken
-- by the latest plan_renews_at (so an active paid org wins over an expired one of the same tier).
-- Users with no owned orgs stay on the FREE/monthly defaults set above.
WITH ranked_orgs AS (
  SELECT
    m.user_id,
    o.plan,
    o.plan_renews_at,
    o.billing_interval,
    o.stripe_customer_id,
    o.bonus_until,
    o.bonus_reason,
    o.bonus_granted_by,
    o.bonus_granted_at,
    o.payment_grace_until,
    ROW_NUMBER() OVER (
      PARTITION BY m.user_id
      ORDER BY
        CASE o.plan
          WHEN 'enterprise' THEN 5
          WHEN 'business'   THEN 4
          WHEN 'pro'        THEN 3
          WHEN 'starter'    THEN 2
          WHEN 'free'       THEN 1
          ELSE 0
        END DESC,
        o.plan_renews_at DESC NULLS LAST,
        o.created_at ASC
    ) AS rn
  FROM org_members m
  JOIN organizations o ON o.id = m.org_id
  WHERE m.role = 'owner'::org_role
)
UPDATE users u
   SET plan                = r.plan,
       plan_renews_at      = r.plan_renews_at,
       billing_interval    = r.billing_interval,
       stripe_customer_id  = r.stripe_customer_id,
       bonus_until         = r.bonus_until,
       bonus_reason        = r.bonus_reason,
       bonus_granted_by    = r.bonus_granted_by,
       bonus_granted_at    = r.bonus_granted_at,
       payment_grace_until = r.payment_grace_until
  FROM ranked_orgs r
 WHERE r.user_id = u.id
   AND r.rn = 1;
