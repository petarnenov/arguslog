package dev.argus.api.application.port;

import dev.argus.api.domain.Issue;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read-side persistence port for the issues table. Implementations decide how to enforce tenancy
 * (RLS in production, no-op in unit tests); the use case is unaware.
 */
public interface IssueRepository {

  /**
   * Returns up to {@code limit} issues for the given project, optionally filtered by status / level
   * and starting strictly after {@code cursor}. Order is {@code (last_seen_at DESC, id DESC)}.
   */
  List<Issue> page(
      long projectId,
      Optional<Issue.Status> status,
      Optional<Issue.Level> level,
      Optional<Cursor> cursor,
      int limit);

  /** Cursor used to seek strictly past a given (last_seen_at, id) tuple. */
  record Cursor(Instant lastSeenAt, long id) {}
}
