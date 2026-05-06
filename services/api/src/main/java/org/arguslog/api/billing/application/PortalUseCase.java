package org.arguslog.api.billing.application;

public interface PortalUseCase {

  /**
   * Creates a Stripe Customer Portal session for the org's existing customer record and returns the
   * hosted URL the caller should redirect to. Throws {@link NoCustomerException} if the org never
   * went through Checkout — the dashboard should disable the "Manage subscription" button in that
   * case.
   */
  String createPortalUrl(long orgId);

  /** The org has no {@code stripe_customer_id} yet — they must Checkout first. */
  final class NoCustomerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NoCustomerException(String message) {
      super(message);
    }
  }
}
