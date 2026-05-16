-- =====================================================================
-- Generic on/off toggle for every alert destination (slack/email/telegram/
-- webhook/github_issue). Today destinations are either present (used) or
-- deleted (not used); pausing forces the operator to delete + lose the
-- encrypted secret in `config_encrypted`, then re-paste it later. With
-- `enabled` they can flip auto-triage / Slack-spam / etc. off without
-- discarding the config.
--
-- Mirrors the existing pattern on alert_rules (V1:191 `enabled BOOLEAN
-- NOT NULL DEFAULT TRUE` + partial index on (project_id) WHERE enabled).
-- The partial index keeps the dispatcher's hot-path lookup
-- ("give me enabled destinations for this org/rule") narrow.
-- =====================================================================

ALTER TABLE alert_destinations
  ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_alert_destinations_enabled
  ON alert_destinations(org_id) WHERE enabled;
