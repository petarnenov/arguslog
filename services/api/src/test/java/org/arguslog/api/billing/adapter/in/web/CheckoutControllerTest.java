package org.arguslog.api.billing.adapter.in.web;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.arguslog.api.billing.application.CheckoutUseCase.CheckoutFailedException;
import org.arguslog.api.billing.application.CheckoutUseCase.StripeNotConfiguredException;
import org.arguslog.api.billing.application.PortalUseCase.NoCustomerException;
import org.arguslog.api.testsupport.AbstractControllerTest;
import org.junit.jupiter.api.Test;

class CheckoutControllerTest extends AbstractControllerTest {

  @Test
  void postReturnsCheckoutUrl() throws Exception {
    // Test profile has SecurityConfig in permit-all mode → no JWT in the security context, so
    // currentUserEmail() returns null. The use case still receives the orgId either way.
    when(checkoutUseCase.createCheckoutUrl(eq(1L), any()))
        .thenReturn("https://checkout.stripe.com/c/abc");

    mvc.perform(post("/api/v1/orgs/1/billing/checkout-session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/c/abc"));
  }

  @Test
  void unconfiguredStripeReturns503ProblemJson() throws Exception {
    when(checkoutUseCase.createCheckoutUrl(eq(1L), any()))
        .thenThrow(new StripeNotConfiguredException("Stripe is not configured"));

    mvc.perform(post("/api/v1/orgs/1/billing/checkout-session"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.title").value(startsWith("Stripe not configured")));
  }

  @Test
  void stripeRejectionReturns502ProblemJson() throws Exception {
    when(checkoutUseCase.createCheckoutUrl(eq(1L), any()))
        .thenThrow(new CheckoutFailedException("rate-limited", new RuntimeException()));

    mvc.perform(post("/api/v1/orgs/1/billing/checkout-session"))
        .andExpect(status().isBadGateway())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.title").value(startsWith("Stripe checkout failed")));
  }

  @Test
  void portalReturnsHostedUrl() throws Exception {
    when(portalUseCase.createPortalUrl(1L)).thenReturn("https://billing.stripe.com/p/sess_xyz");

    mvc.perform(post("/api/v1/orgs/1/billing/portal"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("https://billing.stripe.com/p/sess_xyz"));
  }

  @Test
  void portalWithoutCustomerReturns409ProblemJson() throws Exception {
    when(portalUseCase.createPortalUrl(1L))
        .thenThrow(new NoCustomerException("Org 1 has no Stripe customer yet"));

    mvc.perform(post("/api/v1/orgs/1/billing/portal"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.title").value(startsWith("No Stripe customer")));
  }
}
