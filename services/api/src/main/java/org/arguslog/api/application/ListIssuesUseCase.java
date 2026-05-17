package org.arguslog.api.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.domain.Issue;

/** Inbound port: list a project's issues, sorted by last_seen_at desc, with cursor pagination. */
public interface ListIssuesUseCase {

  Page list(Query query);

  /**
   * Pagination is encoded as an opaque base64 cursor — callers MUST NOT crack it open. Limit is
   * clamped server-side; sane default is 50, cap is 200.
   *
   * <p>{@code searchText} is a free-text ILIKE substring matched against title + culprit. {@code
   * assignee} restricts the result to issues owned by a specific user or to unassigned rows; absent
   * means "any assignee state".
   */
  record Query(
      long projectId,
      Optional<Issue.Status> status,
      Optional<Issue.Level> level,
      Optional<String> searchText,
      Optional<AssigneeFilter> assignee,
      Optional<String> cursor,
      int limit) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public Query {
      if (limit <= 0) limit = DEFAULT_LIMIT;
      if (limit > MAX_LIMIT) limit = MAX_LIMIT;
    }
  }

  /**
   * Filter on the {@code issues.assignee_user_id} column. {@link User} narrows to a specific
   * assignee; {@link #UNASSIGNED} matches only rows where the column is NULL.
   */
  sealed interface AssigneeFilter permits AssigneeFilter.User, AssigneeFilter.Unassigned {
    record User(UUID userId) implements AssigneeFilter {}

    /** Single-instance marker — Issue's assignee column IS NULL. */
    enum Unassigned implements AssigneeFilter {
      INSTANCE
    }

    AssigneeFilter UNASSIGNED = Unassigned.INSTANCE;
  }

  /** {@code nextCursor} is empty when the caller has reached the end of the result set. */
  record Page(List<Issue> issues, Optional<String> nextCursor) {}
}
