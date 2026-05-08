package org.arguslog.api.billing.application;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.billingportal.Session;
import com.stripe.param.billingportal.SessionCreateParams;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.billing.adapter.out.stripe.StripeProperties;
import org.arguslog.api.billing.application.CheckoutUseCase.CheckoutFailedException;
import org.arguslog.api.billing.application.CheckoutUseCase.StripeNotConfiguredException;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.arguslog.api.domain.Org;
import org.springframework.stereotype.Service;

/**
 * Mints a Stripe Customer Portal session — Stripe-hosted page where users can update their card,
 * download invoices, cancel subscriptions, etc. We just hand them the link; everything else is
 * Stripe's UI and webhooks tell us about state changes (P4 #5).
 */
@Service
public class StripePortalService implements PortalUseCase {

  private final StripeClient stripe;
  private final StripeProperties props;
  private final BillingCustomerRepository customers;
  private final OrgWriteRepository orgs;

  public StripePortalService(
      StripeClient stripe,
      StripeProperties props,
      BillingCustomerRepository customers,
      OrgWriteRepository orgs) {
    this.stripe = stripe;
    this.props = props;
    this.customers = customers;
    this.orgs = orgs;
  }

  @Override
  public String createPortalUrl(long orgId) {
    if (!props.configured()) {
      throw new StripeNotConfiguredException(
          "Stripe is not configured on this deployment — set arguslog.stripe.api-key + price-pro-id"
              + " to enable the customer portal.");
    }

    String customerId =
        customers
            .findCustomerId(orgId)
            .orElseThrow(
                () ->
                    new NoCustomerException(
                        "Org " + orgId + " has no Stripe customer yet — run Checkout first."));

    String slug =
        orgs.findById(orgId)
            .map(Org::slug)
            .orElseThrow(
                () ->
                    new CheckoutFailedException(
                        "Org " + orgId + " disappeared between access-guard and portal", null));

    SessionCreateParams params =
        SessionCreateParams.builder()
            .setCustomer(customerId)
            .setReturnUrl(props.portalReturnUrl(slug))
            .build();

    try {
      Session session = stripe.billingPortal().sessions().create(params);
      return session.getUrl();
    } catch (StripeException e) {
      throw new CheckoutFailedException(
          "Stripe rejected the portal session creation: " + e.getMessage(), e);
    }
  }
}
