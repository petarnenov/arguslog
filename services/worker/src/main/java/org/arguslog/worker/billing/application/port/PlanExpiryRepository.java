package org.arguslog.worker.billing.application.port;

import java.time.Instant;
import java.util.List;

/**
 * One-time-purchase model has no auto-renew, so we synthesize a "payment failure" the moment {@code
 * plan_renews_at} elapses on a paying user. Opening a grace window keeps the customer's dashboard
 * read-only-but-not-deleted-yet for the configured grace period; the existing {@code
 * PaymentDowngradeJob} flips them to FREE when grace also lapses.
 *
 * <p>V27+ per-user billing: the grace window is opened on the {@code users} row directly, since
 * billing identity moved off {@code organizations}. The atomic UPDATE+JOIN returns the affected
 * owner-org ids so callers can audit / alert without a second SELECT racing concurrent webhook
 * writers — same return shape as {@code PaymentDowngradeRepository}.
 */
public interface PlanExpiryRepository {

  /**
   * For every user on a paid plan whose {@code plan_renews_at < now} AND no grace window is
   * currently open, set {@code payment_grace_until = now + gracePeriodSeconds}. Returns the
   * owner-org ids resolved from the affected users. Idempotent — running twice in the same minute
   * updates nothing the second time.
   */
  List<Long> openGraceForExpiredPlans(Instant now, long gracePeriodSeconds);
}
