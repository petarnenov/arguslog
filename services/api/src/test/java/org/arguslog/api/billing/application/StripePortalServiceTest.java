package org.arguslog.api.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.billingportal.Session;
import com.stripe.param.billingportal.SessionCreateParams;
import com.stripe.service.BillingPortalService;
import com.stripe.service.billingportal.SessionService;
import java.util.Optional;
import org.arguslog.api.billing.adapter.out.stripe.StripeProperties;
import org.arguslog.api.billing.application.CheckoutUseCase.CheckoutFailedException;
import org.arguslog.api.billing.application.CheckoutUseCase.StripeNotConfiguredException;
import org.arguslog.api.billing.application.PortalUseCase.NoCustomerException;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StripePortalServiceTest {

  @Mock StripeClient stripe;
  @Mock BillingPortalService billingPortal;
  @Mock SessionService sessions;
  @Mock BillingCustomerRepository customers;
  @Mock org.arguslog.api.application.port.OrgWriteRepository orgs;

  StripePortalService service;
  StripeProperties props;

  @BeforeEach
  void setUp() {
    props =
        new StripeProperties("sk_test_123", "whsec_x", "price_pro_test", "", "https://app.example");
    service = new StripePortalService(stripe, props, customers, orgs);
    org.mockito.Mockito.lenient().when(stripe.billingPortal()).thenReturn(billingPortal);
    org.mockito.Mockito.lenient().when(billingPortal.sessions()).thenReturn(sessions);
    org.mockito.Mockito.lenient()
        .when(orgs.findById(1L))
        .thenReturn(
            java.util.Optional.of(
                new org.arguslog.api.domain.Org(
                    1L, "acme", "Acme", "free", java.time.Instant.parse("2026-05-05T00:00:00Z"))));
  }

  @Test
  void unconfiguredStripeThrows503Exception() {
    StripeProperties bad = new StripeProperties("", "", "", "", "https://app.example");
    StripePortalService unconfigured = new StripePortalService(stripe, bad, customers, orgs);
    assertThatThrownBy(() -> unconfigured.createPortalUrl(1L))
        .isInstanceOf(StripeNotConfiguredException.class);
  }

  @Test
  void noCustomerYieldsNoCustomerException() {
    when(customers.findCustomerId(1L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.createPortalUrl(1L))
        .isInstanceOf(NoCustomerException.class)
        .hasMessageContaining("Checkout first");
  }

  @Test
  void existingCustomerProducesPortalSessionWithReturnUrl() throws Exception {
    when(customers.findCustomerId(1L)).thenReturn(Optional.of("cus_42"));
    Session fake = mock(Session.class);
    when(fake.getUrl()).thenReturn("https://billing.stripe.com/p/sess_xyz");
    when(sessions.create(any(SessionCreateParams.class))).thenReturn(fake);

    String url = service.createPortalUrl(1L);

    assertThat(url).isEqualTo("https://billing.stripe.com/p/sess_xyz");
    ArgumentCaptor<SessionCreateParams> cap = ArgumentCaptor.forClass(SessionCreateParams.class);
    org.mockito.Mockito.verify(sessions).create(cap.capture());
    assertThat(cap.getValue().getCustomer()).isEqualTo("cus_42");
    // Slug-based, not numeric — the dashboard router routes by slug.
    assertThat(cap.getValue().getReturnUrl()).isEqualTo("https://app.example/orgs/acme/billing");
  }

  @Test
  void stripeExceptionSurfacesAsCheckoutFailedException() throws Exception {
    when(customers.findCustomerId(1L)).thenReturn(Optional.of("cus_42"));
    when(sessions.create(any(SessionCreateParams.class)))
        .thenThrow(new TestStripeException("portal closed", null));

    assertThatThrownBy(() -> service.createPortalUrl(1L))
        .isInstanceOf(CheckoutFailedException.class)
        .hasMessageContaining("portal closed");
  }

  /** Concrete subclass — StripeException is abstract. */
  private static final class TestStripeException extends StripeException {
    private static final long serialVersionUID = 1L;

    TestStripeException(String message, Integer statusCode) {
      super(message, null, null, statusCode);
    }
  }
}
