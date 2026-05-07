package org.arguslog.api.billing.application;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import java.util.Optional;
import org.arguslog.api.billing.adapter.out.stripe.StripeProperties;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.arguslog.api.billing.domain.BillingInterval;
import org.springframework.stereotype.Service;

/**
 * Builds a Stripe Checkout Session for the org's Pro upgrade. {@code client_reference_id} is set to
 * the orgId so the webhook handler (P4 #5) can map the resulting subscription back to our tenancy
 * row without re-querying Stripe for metadata.
 *
 * <p>Customer handling: if the org already has a {@code stripe_customer_id} (returning customer who
 * once subscribed and then cancelled), reuse it via {@code customer = ...}. Otherwise pass {@code
 * customer_email} so Stripe creates a new customer at checkout time.
 */
@Service
public class StripeCheckoutService implements CheckoutUseCase {

  private final StripeClient stripe;
  private final StripeProperties props;
  private final BillingCustomerRepository customers;

  public StripeCheckoutService(
      StripeClient stripe, StripeProperties props, BillingCustomerRepository customers) {
    this.stripe = stripe;
    this.props = props;
    this.customers = customers;
  }

  @Override
  public String createCheckoutUrl(long orgId, String userEmail, BillingInterval interval) {
    if (!props.configured()) {
      throw new StripeNotConfiguredException(
          "Stripe is not configured on this deployment — set arguslog.stripe.api-key and"
              + " arguslog.stripe.price-pro-id to enable checkout.");
    }
    if (interval == BillingInterval.ANNUAL && !props.annualConfigured()) {
      throw new StripeNotConfiguredException(
          "Annual billing is not configured on this deployment — set"
              + " arguslog.stripe.price-pro-annual-id to enable annual checkout.");
    }

    String priceId =
        interval == BillingInterval.ANNUAL ? props.priceProAnnualId() : props.priceProId();

    SessionCreateParams.Builder params =
        SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
            .setSuccessUrl(props.successUrl(orgId))
            .setCancelUrl(props.cancelUrl(orgId))
            .setClientReferenceId(String.valueOf(orgId))
            .addLineItem(
                SessionCreateParams.LineItem.builder().setPrice(priceId).setQuantity(1L).build());

    Optional<String> existing = customers.findCustomerId(orgId);
    if (existing.isPresent()) {
      // Stripe rejects setting both customer + customer_email; pick one.
      params.setCustomer(existing.get());
    } else if (userEmail != null && !userEmail.isBlank()) {
      params.setCustomerEmail(userEmail);
    }

    try {
      Session session = stripe.checkout().sessions().create(params.build());
      return session.getUrl();
    } catch (StripeException e) {
      throw new CheckoutFailedException(
          "Stripe rejected the checkout session creation: " + e.getMessage(), e);
    }
  }
}
