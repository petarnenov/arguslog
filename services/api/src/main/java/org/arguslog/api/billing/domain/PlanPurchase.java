package org.arguslog.api.billing.domain;

import org.arguslog.billing.PlanTier;

import java.time.Instant;
import java.util.Optional;

/**
 * A successful plan purchase event from any provider. Source of truth for "when does this org's
 * plan expire" and "what did they actually pay" — independent of provider-specific tables.
 *
 * <p>Cross-provider uniqueness is enforced at the DB level on {@code (provider,
 * provider_reference)}: re-delivery of the same Stripe webhook, NOWPayments IPN, or LS event will
 * never double-apply.
 */
public record PlanPurchase(
    long id,
    long orgId,
    BillingProvider provider,
    String providerReference,
    PlanTier plan,
    int durationMonths,
    int amountCents,
    String currency,
    Optional<String> payCurrency,
    Instant appliedAt,
    Instant expiresAt) {

  public PlanPurchase {
    if (durationMonths <= 0) {
      throw new IllegalArgumentException("durationMonths must be positive: " + durationMonths);
    }
    if (amountCents < 0) {
      throw new IllegalArgumentException("amountCents must be non-negative: " + amountCents);
    }
  }
}
