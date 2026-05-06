package org.arguslog.ingest.application.port;

import java.time.LocalDate;

/**
 * Atomic "consume one event from this org's monthly bucket" port. Implementations MUST do the
 * read+increment in one round-trip — checking-then-incrementing on separate calls would let two
 * concurrent ingests both pass the cap.
 */
public interface MonthlyQuotaCounter {

  /**
   * Increments the org's counter for {@code periodStart} if and only if the current value is still
   * strictly below {@code cap}.
   *
   * @return {@code true} if the increment happened (caller may proceed); {@code false} if the cap
   *     was already reached (caller must reject as quota exceeded).
   */
  boolean tryConsume(long orgId, LocalDate periodStart, long cap);
}
