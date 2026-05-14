-- =====================================================================
-- Slack workspace installs. One row per (Slack team install, Arguslog org)
-- pair. Slash commands route from `slack_team_id` → workspace row → org +
-- default_project_id.
--
-- install_token is stored encrypted via the existing SecretCipher
-- (lib/crypto-aes-gcm) — same wire format the api uses for any other
-- third-party bearer secret. The cipher prefix lets the read path detect
-- and reject plaintext rows from any earlier dev seed.
--
-- ON DELETE CASCADE on org_id keeps the workspace install rows in sync
-- when an org is deleted; project FK is ON DELETE SET NULL so a missing
-- default project doesn't orphan the install — slash commands then fail
-- with a "no default project set" message instead of 500.
-- =====================================================================
CREATE TABLE slack_workspaces (
  id                      BIGSERIAL PRIMARY KEY,
  slack_team_id           TEXT NOT NULL UNIQUE,
  slack_team_name         TEXT NOT NULL,
  install_token_encrypted TEXT NOT NULL,
  org_id                  BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  default_project_id      BIGINT NULL REFERENCES projects(id) ON DELETE SET NULL,
  installed_by_user_id    UUID NULL,
  installed_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  deactivated_at          TIMESTAMPTZ NULL
);

-- Lookup by org for the dashboard's "managed Slack workspaces" table; active rows only via
-- a partial filter so the index stays small once deactivations accumulate.
CREATE INDEX idx_slack_workspaces_org_active
  ON slack_workspaces(org_id)
  WHERE deactivated_at IS NULL;

-- RLS: workspace rows are tenant-scoped. The Slack command path runs without an OrgContext
-- (the team_id IS the tenant key), so the dispatcher uses a non-RLS lookup and pins the
-- OrgContext to the row's org_id before calling any downstream repo. Dashboard reads go
-- through the RLS-protected listForOrg().
ALTER TABLE slack_workspaces ENABLE ROW LEVEL SECURITY;

CREATE POLICY slack_workspaces_org_isolation ON slack_workspaces
  USING (org_id = current_setting('arguslog.org_id', true)::BIGINT);
