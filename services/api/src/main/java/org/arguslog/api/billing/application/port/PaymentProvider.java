package org.arguslog.api.billing.application.port;

import org.arguslog.api.billing.domain.BillingProvider;

/**
 * Outbound port for a payment provider. Each implementation (NOWPayments, Lemon Squeezy, Stripe)
 * adapts its provider-specific REST API into a uniform "create a checkout for orgId paying for N
 * months" call. Webhook handling stays per-provider because the IPN/event payloads differ; both
 * code paths funnel results into the same {@code applyPlanPurchase} use case.
 */
public interface PaymentProvider {

  BillingProvider id();

  /**
   * Create a hosted checkout session for {@code orgId} buying {@code durationMonths} of PRO. The
   * returned URL is opened in the user's browser; on completion the provider will hit the service's
   * webhook endpoint to confirm payment.
   */
  CheckoutSession createCheckout(long orgId, int durationMonths);

  record CheckoutSession(String checkoutUrl, String providerReference) {}
}
