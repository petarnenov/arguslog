package org.arguslog.api.billing.application.port;

import java.time.LocalDate;

/**
 * Read-side port for the per-org event-count snapshot. The {@code quotas} row is updated by the
 * ingest tier as events flow through; the api just reads back the current month's counter so the
 * dashboard can render usage without re-counting events.
 */
public interface UsageRepository {

  /**
   * @param orgId the org whose monthly usage we want
   * @param periodStart the first-of-month date that keys the row; pass current month for "now"
   * @return event count for that month, 0 if no row yet (= no events delivered)
   */
  long currentEventCount(long orgId, LocalDate periodStart);
}
