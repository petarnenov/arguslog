package dev.argus.worker.application.port;

import dev.argus.worker.domain.Fingerprint;
import dev.argus.worker.domain.IncomingEvent;

/**
 * Persistence boundary for the worker. Implementations MUST do issue upsert + event insert in a
 * single transaction so a duplicate stream redelivery never produces a half-written event row.
 */
public interface EventStore {

  /**
   * Atomically: (1) upsert the issue keyed by (projectId, environmentId=NULL, fingerprint), (2)
   * bump occurrence_count + last_seen_at, (3) insert the event payload into the events hypertable.
   * Returns the issue id and whether this call created the issue (vs. bumped an existing one).
   */
  PersistResult persist(IncomingEvent event, Fingerprint fingerprint);

  record PersistResult(long issueId, boolean newIssue) {}
}
