package org.arguslog.api.slack.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row from {@code slack_workspaces}. A workspace install pins a single Slack team to an
 * Arguslog org + (optional) default project; every {@code /arguslog …} slash command coming
 * from that workspace routes to those resources. The token field is the OAuth-issued
 * {@code xoxb-…} bot token in plaintext at this layer — the JDBC adapter handles the cipher
 * round-trip so domain code never sees the encrypted blob.
 *
 * <p>{@code deactivatedAt} is the tombstone marker for an uninstall — the row stays so we can
 * surface the install history in the audit log, but slash commands check for null on this
 * field before doing anything.
 */
public record SlackWorkspace(
    long id,
    String slackTeamId,
    String slackTeamName,
    String installToken,
    long orgId,
    Long defaultProjectId,
    UUID installedByUserId,
    Instant installedAt,
    Instant deactivatedAt) {

  public boolean isActive() {
    return deactivatedAt == null;
  }
}
