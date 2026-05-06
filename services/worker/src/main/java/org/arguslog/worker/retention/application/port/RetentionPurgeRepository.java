package org.arguslog.worker.retention.application.port;

import java.time.Instant;

/**
 * Per-org deletion of {@code events} rows older than a cutoff. Implementations are expected to
 * batch internally so a single call cannot lock the table for an unbounded amount of time.
 */
public interface RetentionPurgeRepository {

  /**
   * Deletes up to {@code batchSize} {@code events} rows for the org's projects with {@code
   * received_at < cutoff}. Returns the number of rows actually deleted; the orchestrator loops
   * until this returns less than the batch size.
   */
  int purgeBatch(long orgId, Instant cutoff, int batchSize);

  /**
   * Counts how many {@code events} rows would be deleted by a full purge. Used by dry-run mode so
   * the first deploy logs scope without mutating data.
   */
  long countEligible(long orgId, Instant cutoff);
}
