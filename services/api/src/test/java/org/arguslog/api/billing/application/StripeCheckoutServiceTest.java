package org.arguslog.api.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.service.CheckoutService;
import com.stripe.service.checkout.SessionService;
import java.util.Optional;
import org.arguslog.api.billing.adapter.out.stripe.StripeProperties;
import org.arguslog.api.billing.application.CheckoutUseCase.CheckoutFailedException;
import org.arguslog.api.billing.application.CheckoutUseCase.StripeNotConfiguredException;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.arguslog.api.billing.domain.BillingInterval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StripeCheckoutServiceTest {

  @Mock StripeClient stripe;
  @Mock CheckoutService checkout;
  @Mock SessionService sessions;
  @Mock BillingCustomerRepository customers;
  @Mock org.arguslog.api.application.port.OrgWriteRepository orgs;

  StripeCheckoutService service;
  StripeProperties props;

  @BeforeEach
  void setUp() {
    props =
        new StripeProperties(
            "sk_test_123",
            "whsec_x",
            "price_pro_test",
            "price_pro_annual_test",
            "https://app.example");
    service = new StripeCheckoutService(stripe, props, customers, orgs);
    // lenient — unconfigured-key test never reaches the Stripe SDK so the stubs go unused there.
    org.mockito.Mockito.lenient().when(stripe.checkout()).thenReturn(checkout);
    org.mockito.Mockito.lenient().when(checkout.sessions()).thenReturn(sessions);
    org.mockito.Mockito.lenient()
        .when(orgs.findById(1L))
        .thenReturn(
            java.util.Optional.of(
                new org.arguslog.api.domain.Org(
                    1L, "acme", "Acme", "free", java.time.Instant.parse("2026-05-05T00:00:00Z"))));
  }

  @Test
  void unconfiguredKeyOrPriceRaises503Exception() {
    StripeProperties bad = new StripeProperties("", "", "", "", "https://app.example");
    StripeCheckoutService unconfigured = new StripeCheckoutService(stripe, bad, customers, orgs);
    assertThatThrownBy(
            () -> unconfigured.createCheckoutUrl(1L, "user@example.com", BillingInterval.MONTHLY))
        .isInstanceOf(StripeNotConfiguredException.class);
  }

  @Test
  void annualWithoutAnnualPriceConfiguredRaises503() {
    StripeProperties noAnnual =
        new StripeProperties("sk_test_123", "whsec_x", "price_pro_test", "", "https://app.example");
    StripeCheckoutService monthlyOnly =
        new StripeCheckoutService(stripe, noAnnual, customers, orgs);
    assertThatThrownBy(
            () -> monthlyOnly.createCheckoutUrl(1L, "user@example.com", BillingInterval.ANNUAL))
        .isInstanceOf(StripeNotConfiguredException.class)
        .hasMessageContaining("annual");
  }

  @Test
  void annualUsesAnnualPriceId() throws Exception {
    when(customers.findCustomerId(1L)).thenReturn(Optional.empty());
    Session fake = mock(Session.class);
    when(fake.getUrl()).thenReturn("https://checkout.stripe.com/c/annual");
    when(sessions.create(any(SessionCreateParams.class))).thenReturn(fake);

    service.createCheckoutUrl(1L, "user@example.com", BillingInterval.ANNUAL);

    ArgumentCaptor<SessionCreateParams> cap = ArgumentCaptor.forClass(SessionCreateParams.class);
    org.mockito.Mockito.verify(sessions).create(cap.capture());
    assertThat(cap.getValue().getLineItems().get(0).getPrice()).isEqualTo("price_pro_annual_test");
  }

  @Test
  void newCustomerSessionCarriesEmailAndOrgIdAsClientReference() throws Exception {
    when(customers.findCustomerId(1L)).thenReturn(Optional.empty());
    Session fake = mock(Session.class);
    when(fake.getUrl()).thenReturn("https://checkout.stripe.com/c/abc");
    when(sessions.create(any(SessionCreateParams.class))).thenReturn(fake);

    String url = service.createCheckoutUrl(1L, "user@example.com", BillingInterval.MONTHLY);

    assertThat(url).isEqualTo("https://checkout.stripe.com/c/abc");
    ArgumentCaptor<SessionCreateParams> cap = ArgumentCaptor.forClass(SessionCreateParams.class);
    org.mockito.Mockito.verify(sessions).create(cap.capture());
    SessionCreateParams params = cap.getValue();
    assertThat(params.getClientReferenceId()).isEqualTo("1");
    assertThat(params.getCustomerEmail()).isEqualTo("user@example.com");
    assertThat(params.getCustomer()).isNull();
    assertThat(params.getMode()).isEqualTo(SessionCreateParams.Mode.SUBSCRIPTION);
    assertThat(params.getLineItems()).hasSize(1);
    assertThat(params.getLineItems().get(0).getPrice()).isEqualTo("price_pro_test");
    // The success URL must use the org slug (not the numeric id) — the dashboard router keys
    // org-scoped routes on slug.
    assertThat(params.getSuccessUrl()).contains("/orgs/acme/billing");
    assertThat(params.getSuccessUrl()).contains("checkout=success");
    assertThat(params.getSuccessUrl()).doesNotContain("/orgs/1/billing");
    assertThat(params.getCancelUrl()).contains("/orgs/acme/billing");
    assertThat(params.getCancelUrl()).contains("checkout=cancelled");
  }

  @Test
  void existingCustomerReusesIdAndDropsEmailField() throws Exception {
    when(customers.findCustomerId(1L)).thenReturn(Optional.of("cus_existing_42"));
    Session fake = mock(Session.class);
    when(fake.getUrl()).thenReturn("https://checkout.stripe.com/c/xyz");
    when(sessions.create(any(SessionCreateParams.class))).thenReturn(fake);

    service.createCheckoutUrl(1L, "user@example.com", BillingInterval.MONTHLY);

    ArgumentCaptor<SessionCreateParams> cap = ArgumentCaptor.forClass(SessionCreateParams.class);
    org.mockito.Mockito.verify(sessions).create(cap.capture());
    // Stripe rejects setting both customer + customer_email; existing customer wins.
    assertThat(cap.getValue().getCustomer()).isEqualTo("cus_existing_42");
    assertThat(cap.getValue().getCustomerEmail()).isNull();
  }

  @Test
  void stripeExceptionSurfacesAsCheckoutFailedException() throws Exception {
    when(customers.findCustomerId(1L)).thenReturn(Optional.empty());
    when(sessions.create(any(SessionCreateParams.class)))
        .thenThrow(new TestStripeException("rate-limited", null));

    assertThatThrownBy(
            () -> service.createCheckoutUrl(1L, "user@example.com", BillingInterval.MONTHLY))
        .isInstanceOf(CheckoutFailedException.class)
        .hasMessageContaining("rate-limited");
  }

  /** Concrete subclass — StripeException is abstract so we need a stub for tests. */
  private static final class TestStripeException extends StripeException {
    private static final long serialVersionUID = 1L;

    TestStripeException(String message, Integer statusCode) {
      super(message, null, null, statusCode);
    }
  }
}
