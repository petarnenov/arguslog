package org.arguslog.worker.application.port;

import java.time.Instant;
import org.arguslog.worker.domain.Fingerprint;
import org.arguslog.worker.domain.IncomingEvent;

/**
 * Persistence boundary for the worker. Implementations MUST do issue upsert + event insert in a
 * single transaction so a duplicate stream redelivery never produces a half-written event row.
 */
public interface EventStore {

  /**
   * Atomically: (1) upsert the issue keyed by (projectId, environmentId=NULL, fingerprint), (2)
   * bump occurrence_count + last_seen_at, (3) insert the event payload into the events hypertable.
   * The {@code releaseVersion} (read from {@code payload.release}, may be {@code null}) is
   * attributed to the issue ONLY on first insert — later events on the same fingerprint never
   * overwrite first_seen_release_id, so the "first seen in vX.Y.Z" claim is stable.
   * Returns the post-upsert issue snapshot the rule evaluator needs (level / first_seen /
   * occurrence_count) so we don't pay a re-SELECT per event.
   */
  PersistResult persist(IncomingEvent event, Fingerprint fingerprint, String releaseVersion);

  record PersistResult(
      long issueId,
      boolean newIssue,
      String level,
      Instant firstSeenAt,
      Instant lastSeenAt,
      long occurrenceCount) {}
}
