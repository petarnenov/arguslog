package org.arguslog.api.application;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.domain.Issue;

/** Inbound port: list a project's issues, sorted by last_seen_at desc, with cursor pagination. */
public interface ListIssuesUseCase {

  Page list(Query query);

  /**
   * Pagination is encoded as an opaque base64 cursor — callers MUST NOT crack it open. Limit is
   * clamped server-side; sane default is 50, cap is 200.
   */
  record Query(
      long projectId,
      Optional<Issue.Status> status,
      Optional<Issue.Level> level,
      Optional<String> cursor,
      int limit) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public Query {
      if (limit <= 0) limit = DEFAULT_LIMIT;
      if (limit > MAX_LIMIT) limit = MAX_LIMIT;
    }
  }

  /** {@code nextCursor} is empty when the caller has reached the end of the result set. */
  record Page(List<Issue> issues, Optional<String> nextCursor) {}
}
