package org.arguslog.api.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.application.CursorCodec;
import org.arguslog.api.application.ListIssuesUseCase.AssigneeFilter;
import org.arguslog.api.domain.Issue;

/**
 * Persistence port for the issues table. Implementations decide how to enforce tenancy (RLS in
 * production, no-op in unit tests); the use case is unaware.
 */
public interface IssueRepository {

  /**
   * Returns up to {@code limit} issues for the given project, filtered by every present option,
   * starting strictly after {@code cursor}. Order is {@code (last_seen_at DESC, id DESC)}.
   *
   * <p>{@code searchText} is matched ILIKE-substring across {@code title + culprit}. {@code
   * assignee} narrows to a specific user, to unassigned rows, or to any state when absent.
   */
  List<Issue> page(
      long projectId,
      Optional<Issue.Status> status,
      Optional<Issue.Level> level,
      Optional<String> searchText,
      Optional<AssigneeFilter> assignee,
      Optional<CursorCodec.LongCursor> cursor,
      int limit);

  /**
   * Returns the issue with {@code issueId} when it belongs to {@code projectId}. Empty either way —
   * caller never gets to distinguish "doesn't exist" from "wrong project" (Sentry-style 404).
   */
  Optional<Issue> findByProjectAndId(long projectId, long issueId);

  /**
   * Updates an issue's status. Returns the refreshed row; empty if the (projectId, issueId) pair
   * does not exist. Caller is responsible for membership / authorization checks beforehand.
   */
  Optional<Issue> updateStatus(long projectId, long issueId, Issue.Status status);

  /**
   * Sets or clears the assignee on an issue. Pass {@code null} to unassign. Returns the refreshed
   * row; empty if the (projectId, issueId) pair does not exist. Caller validates that the assignee
   * is a member of the org.
   */
  Optional<Issue> updateAssignee(long projectId, long issueId, UUID assigneeUserId);

  /**
   * Returns up to {@code limit} issues whose {@code first_seen_release_id = releaseId}, ordered by
   * first_seen_at desc, id desc — newest within the release first. Uses the partial index from
   * V35 ({@code idx_issues_first_seen_release}), so the scan stays bounded even on projects with
   * a very large issue count. No cursor: the per-release list is expected to be small (regression
   * watchlist), and the dashboard renders all of them in a single card.
   */
  List<Issue> listIntroducedInRelease(long projectId, long releaseId, int limit);

  /**
   * Persists the auto-triage agent's analysis on an issue. Sets {@code ai_analysis},
   * {@code ai_analysis_model}, and stamps {@code ai_analyzed_at = now()} in one UPDATE. Returns
   * the refreshed row; empty if the (projectId, issueId) pair does not exist. The endpoint is
   * idempotent — re-running with new content overwrites the prior analysis (no history yet).
   */
  Optional<Issue> updateAiAnalysis(long projectId, long issueId, String analysis, String model);
}
