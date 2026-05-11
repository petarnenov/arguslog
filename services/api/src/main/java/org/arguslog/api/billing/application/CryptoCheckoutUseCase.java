package org.arguslog.api.billing.application;

import org.arguslog.billing.PlanTier;

/**
 * Mints a NOWPayments hosted checkout for {@code orgId} buying {@code durationMonths} of {@code
 * tier}. Returns the checkout URL the user should be redirected to.
 */
public interface CryptoCheckoutUseCase {

  CheckoutResult start(long orgId, PlanTier tier, int durationMonths);

  record CheckoutResult(String checkoutUrl, String invoiceReference) {}
}
