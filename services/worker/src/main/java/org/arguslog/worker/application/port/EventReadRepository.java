package org.arguslog.worker.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * Read-only port for pulling the most recent event payload of an issue. Used by the
 * {@code GithubIssueAlertDispatcher} so it can include a real stack trace + breadcrumbs in the
 * GitHub issue body it creates for Copilot's coding agent — without that, Copilot has nothing
 * concrete to grep for.
 *
 * <p>Worker's other event paths are write-only ({@code EventStore}). This is a one-method read
 * port so the dispatcher doesn't have to round-trip through the api service or hold an
 * {@code EventStore} reference for read concerns.
 */
public interface EventReadRepository {

  /**
   * Returns the latest event's parsed JSON payload for the given (projectId, issueId) pair, or
   * empty when no events exist yet. „Latest" is ordered by {@code received_at DESC} — the same
   * order the dashboard's IssueDetailPage events list uses.
   */
  Optional<JsonNode> findLatestPayloadForIssue(long projectId, long issueId);
}
