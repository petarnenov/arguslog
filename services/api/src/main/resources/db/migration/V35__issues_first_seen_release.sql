-- =====================================================================
-- Attribution: which release was an issue's `release` tag pointing at the
-- first time it appeared? Stored on the issue row itself so the dashboard
-- can render "first seen in v2.1.0" without joining events on every load.
--
-- Worker populates this column on INSERT only — the column is "first
-- seen" by definition, so subsequent events on the same issue do NOT
-- overwrite it even if they tag a different release. The "still seeing
-- this in v2.1.1" story is implicit via the lastSeenAt timestamp.
--
-- ON DELETE SET NULL keeps the issue alive when an operator drops a
-- release; the value goes from "first seen in v2.1.0" to "first seen in
-- a now-deleted release" which the UI renders as the version string
-- stored on the (still present) first event row.
-- =====================================================================
ALTER TABLE issues
  ADD COLUMN first_seen_release_id BIGINT NULL
    REFERENCES releases(id) ON DELETE SET NULL;

-- Partial index covers "list issues first seen in this release" queries (regression
-- detection feed). Most rows will start NULL, so partial keeps the index small.
CREATE INDEX idx_issues_first_seen_release
  ON issues(first_seen_release_id, project_id)
  WHERE first_seen_release_id IS NOT NULL;
