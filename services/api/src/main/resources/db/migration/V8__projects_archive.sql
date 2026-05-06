-- Soft-archive support for projects: a NULL archived_at means "live"; setting
-- it hides the project from the default project list while preserving its
-- issues/events/keys/etc. for historical incident review.
--
-- Hard-delete is intentionally not exposed at the project level — once an
-- issue accrues events, dropping the parent project loses incident history
-- the on-call would still want to scroll back through.
ALTER TABLE projects
  ADD COLUMN archived_at TIMESTAMPTZ;

-- Partial index keeps the live-list lookup `(org_id, archived_at IS NULL)`
-- index-only without bloating it with archived rows.
CREATE INDEX idx_projects_org_live
  ON projects(org_id)
  WHERE archived_at IS NULL;
