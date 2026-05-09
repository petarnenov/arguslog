package org.arguslog.api.billing.application;

/**
 * Mints a NOWPayments hosted checkout for {@code orgId} buying {@code durationMonths} of PRO.
 * Returns the checkout URL the user should be redirected to.
 */
public interface CryptoCheckoutUseCase {

  CheckoutResult start(long orgId, int durationMonths);

  record CheckoutResult(String checkoutUrl, String invoiceReference) {}
}
