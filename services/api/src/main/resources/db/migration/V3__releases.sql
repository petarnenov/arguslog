-- =====================================================================
-- Releases RLS — V1 created the table without isolation, which means
-- a tenant could query releases from another org by guessing project_id.
-- We're about to expose `releases` over the public API, so plug the gap.
--
-- Same isolation pattern as alert_rules: derive org from the project row
-- so RLS works without forcing every query to join organizations.
-- =====================================================================
ALTER TABLE releases ENABLE ROW LEVEL SECURITY;

CREATE POLICY releases_org_isolation ON releases
  USING (project_id IN (
    SELECT id FROM projects WHERE org_id = current_setting('arguslog.org_id', true)::BIGINT
  ));

-- Listing always sorts by created_at DESC (newest releases first); the existing
-- (project_id) index forces a sort. A descending composite covers the page query.
CREATE INDEX idx_releases_project_created ON releases(project_id, created_at DESC);
