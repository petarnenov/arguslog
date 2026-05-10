-- =====================================================================
-- V25: platform admin features.
--
-- 1. organizations gets bonus columns so a platform admin can comp a paid
--    plan to a customer without going through billing. The plan column
--    itself stays the source of truth for caps / quotas; the bonus_*
--    columns are pure metadata letting the dashboard render a banner and
--    letting admins see which plans are paid vs gifted.
--
-- 2. admin_audit_log captures every state-changing admin action — grant,
--    revoke, future suspend/unsuspend — so we have a forensic trail. No
--    UPDATE / DELETE on this table; rows are append-only.
-- =====================================================================

ALTER TABLE organizations
  ADD COLUMN bonus_until       TIMESTAMPTZ,
  ADD COLUMN bonus_granted_by  UUID,
  ADD COLUMN bonus_granted_at  TIMESTAMPTZ,
  ADD COLUMN bonus_reason      TEXT;

CREATE INDEX idx_orgs_bonus_active
  ON organizations(bonus_until)
  WHERE bonus_until IS NOT NULL;

CREATE TABLE admin_audit_log (
  id           BIGSERIAL PRIMARY KEY,
  ts           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  admin_user   UUID,
  admin_email  TEXT NOT NULL,
  action       TEXT NOT NULL,
  target_type  TEXT NOT NULL,
  target_id    TEXT NOT NULL,
  payload      JSONB NOT NULL DEFAULT '{}'::jsonb,
  ip           INET
);

CREATE INDEX idx_admin_audit_ts        ON admin_audit_log(ts DESC);
CREATE INDEX idx_admin_audit_admin     ON admin_audit_log(admin_email, ts DESC);
CREATE INDEX idx_admin_audit_target    ON admin_audit_log(target_type, target_id, ts DESC);
