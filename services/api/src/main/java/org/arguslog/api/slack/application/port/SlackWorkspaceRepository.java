package org.arguslog.api.slack.application.port;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.slack.domain.SlackWorkspace;

/**
 * Read port for slack_workspaces.
 *
 * <p>{@link #findActiveByTeamId} runs OUTSIDE an RLS context — the Slack slash-command path gets a
 * team id from the request and has to look up the row to know which org_id to pin. Every other read
 * goes through {@link #listForOrg}, which assumes the caller already pinned {@code arguslog.org_id}
 * via OrgContext.
 */
public interface SlackWorkspaceRepository {

  /**
   * Bypasses RLS by design — the dispatcher needs to discover the org BEFORE it can pin the RLS
   * context. The slack_team_id column has a unique index, so this is one row at most. Returns empty
   * for unknown teams OR for teams that were uninstalled (deactivated_at NOT NULL).
   */
  Optional<SlackWorkspace> findActiveByTeamId(String slackTeamId);

  /** Dashboard surface — RLS-protected, listOrgId comes from OrgContext. */
  List<SlackWorkspace> listForOrg(long orgId);
}
