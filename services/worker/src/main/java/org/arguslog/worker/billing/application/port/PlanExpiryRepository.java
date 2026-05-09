package org.arguslog.worker.billing.application.port;

import java.time.Instant;
import java.util.List;

/**
 * One-time-purchase model has no auto-renew, so we synthesize a "payment failure" the moment
 * {@code plan_renews_at} elapses on a Pro org. Opening a grace window keeps the customer's
 * dashboard read-only-but-not-deleted-yet for the configured grace period; the existing
 * {@code PaymentDowngradeJob} flips them to FREE when grace also lapses.
 *
 * <p>The atomic UPDATE returns the affected ids so the worker can audit-log without a second
 * SELECT racing concurrent webhook writers.
 */
public interface PlanExpiryRepository {

  /**
   * For every Pro org whose {@code plan_renews_at < now} AND no grace window is currently open,
   * set {@code payment_grace_until = now + gracePeriodSeconds}. Returns the org ids actually
   * updated. Idempotent — running twice in the same minute updates nothing the second time.
   */
  List<Long> openGraceForExpiredPlans(Instant now, long gracePeriodSeconds);
}
