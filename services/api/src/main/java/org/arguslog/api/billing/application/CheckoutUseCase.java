package org.arguslog.api.billing.application;

import org.arguslog.api.billing.domain.BillingInterval;

public interface CheckoutUseCase {

  /**
   * Creates a Stripe Checkout Session for the org's Pro upgrade and returns the hosted URL the
   * caller should redirect the user to.
   *
   * @param orgId tenant identifier — Stripe gets it as {@code client_reference_id} so the webhook
   *     handler (P4 #5) can map back to our row.
   * @param userEmail prefills the Checkout form when the org has no Stripe customer yet.
   * @param interval monthly subscription or annual prepay — determines which Stripe Price ID is
   *     attached to the session.
   */
  String createCheckoutUrl(long orgId, String userEmail, BillingInterval interval);

  /** Thrown when the api hasn't been configured with a Stripe key + price id. */
  final class StripeNotConfiguredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StripeNotConfiguredException(String message) {
      super(message);
    }
  }

  /** Thrown when Stripe rejects the request — bad price id, network error, etc. */
  final class CheckoutFailedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CheckoutFailedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
