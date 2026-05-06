package org.arguslog.worker.billing.application.port;

import java.time.Instant;
import java.util.List;

/**
 * Atomic "downgrade orgs whose grace lapsed" port. The single statement returns the affected org
 * ids so the caller can audit-log without a follow-up read that would race other writers.
 */
public interface PaymentDowngradeRepository {

  /**
   * Atomically sets {@code plan = 'free'}, clears {@code payment_grace_until} and {@code
   * plan_renews_at} for every Pro org whose grace window expired before {@code now}. Returns the
   * ids of orgs actually downgraded (empty list when nothing to do).
   */
  List<Long> downgradeExpired(Instant now);
}
