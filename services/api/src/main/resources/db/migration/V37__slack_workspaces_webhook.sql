-- =====================================================================
-- Capture the incoming-webhook URL + channel Slack returns alongside the
-- bot token at install. With the `incoming-webhook` scope (which we
-- already request), Slack's oauth.v2.access response includes
-- `incoming_webhook.url` + `incoming_webhook.channel` — a workspace-
-- channel-bound POST URL that authorizes itself by possession.
--
-- Storing it lets the dashboard one-click create an Alert Destination
-- pointing at the user's Slack channel without making them dig the URL
-- out of Slack's admin UI.
--
-- Encrypted via the existing SecretCipher — same wire format as
-- install_token_encrypted. Nullable because:
--   • a reinstall under a future scope set that drops incoming-webhook
--     would otherwise force schema gymnastics, and
--   • rows from before this migration land with no webhook captured;
--     the dashboard renders the "Create alert destination" button only
--     when this column is non-null, so legacy rows degrade gracefully.
-- =====================================================================
ALTER TABLE slack_workspaces
  ADD COLUMN webhook_url_encrypted TEXT NULL,
  ADD COLUMN webhook_channel       TEXT NULL;
