-- =====================================================================
-- Issue-level AI analysis blob — written by the auto-triage agent on a
-- new-error alert webhook. The agent fetches the issue + recent events
-- via MCP, generates a root-cause hypothesis + suggested fix, and
-- PATCH-es the result back through /api/v1/projects/{pid}/issues/{iid}
-- /ai-analysis. The dashboard's IssueDetailPage renders the markdown
-- body in a dedicated card.
--
-- Three nullable columns instead of a sub-table:
--   - analysis text is opaque markdown; never queried across rows.
--   - model is tracked so a future "re-run with newer model" affordance
--     can see what was used.
--   - analyzed_at lets the UI dim stale analyses (e.g. older than the
--     last issue event).
-- No index — the access pattern is "fetch by issue id and render".
-- =====================================================================

ALTER TABLE issues
  ADD COLUMN ai_analysis        TEXT,
  ADD COLUMN ai_analysis_model  VARCHAR(64),
  ADD COLUMN ai_analyzed_at     TIMESTAMPTZ;
