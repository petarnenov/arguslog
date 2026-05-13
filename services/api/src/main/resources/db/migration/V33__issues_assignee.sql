-- =====================================================================
-- Issue triage: assignee tracking
--
-- Adds a nullable assignee_user_id FK so an org member can "own" an
-- issue (resolve / investigate / etc). Status mutation (resolve, ignore,
-- regression flip) doesn't need a schema change — the issue_status enum
-- and the issues.status column already exist from V1.
-- =====================================================================

ALTER TABLE issues
    ADD COLUMN assignee_user_id UUID NULL REFERENCES users(id) ON DELETE SET NULL;

-- Partial index — only assigned issues are interesting for lookups; null
-- rows would just bloat the index without ever being filtered on.
CREATE INDEX idx_issues_assignee
    ON issues(project_id, assignee_user_id, last_seen_at DESC)
    WHERE assignee_user_id IS NOT NULL;
