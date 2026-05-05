package org.arguslog.api.application.port;

import org.arguslog.api.application.CursorCodec;
import org.arguslog.api.domain.Issue;
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
      Optional<CursorCodec.LongCursor> cursor,
      int limit);

  /**
   * Returns the issue with {@code issueId} when it belongs to {@code projectId}. Empty either way —
   * caller never gets to distinguish "doesn't exist" from "wrong project" (Sentry-style 404).
   */
  Optional<Issue> findByProjectAndId(long projectId, long issueId);
}
