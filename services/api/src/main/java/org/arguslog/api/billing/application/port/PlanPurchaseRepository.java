package org.arguslog.api.billing.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.billing.domain.BillingProvider;
import org.arguslog.api.billing.domain.PlanPurchase;
import org.arguslog.api.billing.domain.PlanTier;

/**
 * Persistence port for {@link PlanPurchase}. The {@link #recordIfNew} contract guarantees
 * cross-provider idempotency: re-delivery of the same provider event will not apply a purchase
 * twice. Other reads support expiry/reminder jobs and accounting export.
 */
public interface PlanPurchaseRepository {

  /**
   * Insert a new purchase if {@code (provider, providerReference)} hasn't been seen. Returns the
   * stored row (newly inserted or pre-existing) so the caller can read back the assigned id and
   * the canonical {@code applied_at}.
   */
  PlanPurchase recordIfNew(
      long orgId,
      BillingProvider provider,
      String providerReference,
      PlanTier plan,
      int durationMonths,
      int amountCents,
      String currency,
      Optional<String> payCurrency,
      Instant expiresAt);

  /** The most recent purchase for an org, regardless of provider. */
  Optional<PlanPurchase> findLatestForOrg(long orgId);

  /** All purchases for an org, newest first. */
  List<PlanPurchase> listForOrg(long orgId);

  /**
   * Purchases that expire between {@code from} (inclusive) and {@code to} (exclusive). Used by
   * the renewal reminder job.
   */
  List<PlanPurchase> findExpiringBetween(Instant from, Instant to);
}
