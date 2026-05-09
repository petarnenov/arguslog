package org.arguslog.api.billing.application;

import java.util.Optional;
import org.arguslog.api.billing.domain.BillingProvider;
import org.arguslog.api.billing.domain.PlanPurchase;
import org.arguslog.api.billing.domain.PlanTier;

/**
 * Applies a successful plan purchase from any provider. Records the purchase row, extends the
 * org's {@code plan_renews_at} from the later of (now, current renewal) by the purchased months,
 * and sets {@code billing_interval} to match the duration. Idempotent — re-delivering the same
 * provider event returns the previously recorded purchase without double-applying.
 */
public interface ApplyPlanPurchaseUseCase {

  PlanPurchase apply(
      long orgId,
      BillingProvider provider,
      String providerReference,
      PlanTier plan,
      int durationMonths,
      int amountCents,
      String currency,
      Optional<String> payCurrency);
}
