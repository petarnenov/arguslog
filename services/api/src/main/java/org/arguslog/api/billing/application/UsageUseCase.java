package org.arguslog.api.billing.application;

import java.time.Instant;
import java.util.Optional;
import org.arguslog.api.billing.domain.PlanTier;

public interface UsageUseCase {

  Optional<UsageSnapshot> snapshot(long orgId);

  /**
   * Read-side view of "where is this org against its monthly cap right now". The dashboard polls
   * this for the BillingPage banner; the upgrade CTA fires when {@link #ratio} ≥ 0.9.
   *
   * <p>{@code paymentGraceUntil} is non-null only after a {@code invoice.payment_failed} webhook —
   * the BillingPage shows a red "Update payment method" banner with a countdown that links to the
   * existing Stripe customer portal endpoint.
   */
  record UsageSnapshot(
      PlanTier plan,
      long eventsUsed,
      long eventCap,
      double ratio,
      boolean exceeded,
      Instant paymentGraceUntil) {}
}
