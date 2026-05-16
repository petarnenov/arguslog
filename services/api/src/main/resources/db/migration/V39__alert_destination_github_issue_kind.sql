-- =====================================================================
-- Adds `github_issue` to the destination_kind enum so the new
-- GithubIssueAlertDispatcher (worker) can route on it. The dispatcher
-- POSTs a GitHub Issues API create call with `assignees: [copilot-swe-agent]`
-- and a markdown body containing the stack trace + breadcrumbs — Copilot's
-- coding agent picks the issue up and opens a draft PR.
--
-- Append-only on the enum — no table rewrite, no data migration. Existing
-- destinations (slack/email/telegram/webhook) are unaffected.
-- =====================================================================

ALTER TYPE destination_kind ADD VALUE 'github_issue';
