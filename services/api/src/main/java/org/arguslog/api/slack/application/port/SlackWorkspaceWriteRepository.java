package org.arguslog.api.slack.application.port;

import java.util.UUID;
import org.arguslog.api.slack.domain.SlackWorkspace;

/**
 * Write port for slack_workspaces. {@link #upsert} runs at OAuth-callback time; the
 * (slack_team_id) unique index makes this idempotent — re-installing the app on the same
 * Slack team rotates the token without producing a duplicate row.
 */
public interface SlackWorkspaceWriteRepository {

  /**
   * Inserts the workspace install, or updates an existing one (same {@code slackTeamId}) with
   * a fresh token + new {@code orgId} mapping. Existing {@code deactivatedAt} is cleared on
   * conflict so a reinstall after uninstall produces a live row.
   */
  SlackWorkspace upsert(
      String slackTeamId,
      String slackTeamName,
      String installToken,
      long orgId,
      Long defaultProjectId,
      UUID installedByUserId);

  /**
   * Marks the workspace as uninstalled. The row stays for audit; future slash-command lookups
   * for this team will miss the active filter.
   */
  void deactivate(long workspaceId);

  /** Updates the workspace's default project. Used by a future "set project" slash command. */
  SlackWorkspace setDefaultProject(long workspaceId, Long defaultProjectId);
}
