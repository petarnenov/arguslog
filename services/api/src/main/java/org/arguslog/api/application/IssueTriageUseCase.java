package org.arguslog.api.application;

import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.domain.Issue;

/**
 * Triage actions on a single issue. Status mutation (resolve/ignore/reopen) is unconditional —
 * any org member can act. Assignee mutation validates that the picked user is also a member of
 * the same org, otherwise we'd let outside accounts get attached to issues.
 */
public interface IssueTriageUseCase {

  Optional<Issue> updateStatus(long orgId, long projectId, long issueId, Issue.Status status);

  /**
   * Pass {@code null} for {@code assigneeUserId} to unassign. Returns empty when the issue cannot
   * be found OR when the (orgId, projectId) pair doesn't match (Sentry-style 404 collapsing).
   */
  Optional<Issue> updateAssignee(long orgId, long projectId, long issueId, UUID assigneeUserId);

  /**
   * Auto-triage agent's hook: write a freshly-generated root-cause analysis + suggested-fix
   * blob back onto the issue. Body is opaque markdown; {@code model} is the agent's self-
   * reported model id (e.g. {@code claude-opus-4-7}). Returns empty when the issue cannot be
   * found.
   *
   * <p>Deliberately does NOT touch status or assignee — the human still owns the triage
   * decision. The endpoint is also explicitly free of any event-emit side-effects, so a
   * webhook alert rule firing on „new error event" can't infinite-loop on the agent's own
   * write.
   */
  Optional<Issue> attachAiAnalysis(
      long orgId, long projectId, long issueId, String analysis, String model);

  /** Thrown when the assignee target is not a member of the org owning the issue. */
  final class InvalidAssigneeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidAssigneeException(String message) {
      super(message);
    }
  }

  /** Thrown when the AI analysis payload is missing / blank / oversized. */
  final class InvalidAiAnalysisException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidAiAnalysisException(String message) {
      super(message);
    }
  }
}
