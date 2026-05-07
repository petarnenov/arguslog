package org.arguslog.api.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.model.checkout.Session;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.billing.adapter.out.stripe.StripeProperties;
import org.arguslog.api.billing.application.StripeWebhookUseCase.Outcome;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.arguslog.api.billing.application.port.StripeEventLog;
import org.arguslog.api.billing.domain.BillingInterval;
import org.arguslog.api.billing.domain.PlanTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
// Lenient because the stub-helper sets up shared Event/Subscription getters that not every test
// path consults — strict mode would flag the unused branches.
@MockitoSettings(strictness = Strictness.LENIENT)
class StripeWebhookServiceTest {

  @Mock StripeEventLog eventLog;
  @Mock BillingCustomerRepository customers;

  StripeWebhookService service;

  private static final Instant NOW = Instant.parse("2026-05-06T14:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final Duration GRACE = Duration.ofDays(7);
  private static final StripeProperties PROPS =
      new StripeProperties(
          "sk_test_123", "whsec_x", "price_pro_monthly", "price_pro_annual", "https://app.example");

  @BeforeEach
  void setUp() {
    service = new StripeWebhookService(eventLog, customers, PROPS, FIXED, GRACE);
  }

  @Test
  void duplicateEventIdsShortCircuit() {
    Event event = stubEvent("evt_dup", "checkout.session.completed", null);
    when(eventLog.recordIfNew("evt_dup", "checkout.session.completed")).thenReturn(false);

    assertThat(service.handle(event)).isEqualTo(Outcome.ALREADY_SEEN);
    verify(customers, never()).saveCustomerId(anyLong(), anyString());
  }

  @Test
  void unknownEventTypeIsAckedAsIgnored() {
    Event event = stubEvent("evt_x", "customer.created", null);
    when(eventLog.recordIfNew("evt_x", "customer.created")).thenReturn(true);

    assertThat(service.handle(event)).isEqualTo(Outcome.IGNORED);
  }

  @Test
  void checkoutCompletedBindsCustomerAndSetsPlanToPro() {
    Session session = mock(Session.class);
    when(session.getClientReferenceId()).thenReturn("42");
    when(session.getCustomer()).thenReturn("cus_xyz");

    Event event = stubEvent("evt_chk", "checkout.session.completed", session);
    when(eventLog.recordIfNew("evt_chk", "checkout.session.completed")).thenReturn(true);

    Outcome out = service.handle(event);
    assertThat(out).isEqualTo(Outcome.PROCESSED);
    verify(customers).saveCustomerId(42L, "cus_xyz");
    verify(customers).updatePlanAndRenewal(42L, PlanTier.PRO.dbValue(), null);
  }

  @Test
  void checkoutCompletedMissingFieldsIgnored() {
    Session session = mock(Session.class);
    when(session.getClientReferenceId()).thenReturn(null);
    when(session.getCustomer()).thenReturn("cus_xyz");

    Event event = stubEvent("evt_bad", "checkout.session.completed", session);
    when(eventLog.recordIfNew("evt_bad", "checkout.session.completed")).thenReturn(true);

    assertThat(service.handle(event)).isEqualTo(Outcome.IGNORED);
    verify(customers, never()).saveCustomerId(anyLong(), anyString());
  }

  @Test
  void subscriptionUpdatedActiveStatusKeepsProAndRefreshesRenewal() {
    long renewalEpoch = Instant.parse("2026-06-01T00:00:00Z").getEpochSecond();
    Subscription sub = stubSubscription("cus_xyz", "active", renewalEpoch);

    Event event = stubEvent("evt_upd", "customer.subscription.updated", sub);
    when(eventLog.recordIfNew("evt_upd", "customer.subscription.updated")).thenReturn(true);
    when(customers.findOrgIdByCustomerId("cus_xyz")).thenReturn(Optional.of(42L));

    assertThat(service.handle(event)).isEqualTo(Outcome.PROCESSED);
    verify(customers)
        .updatePlanAndRenewal(42L, PlanTier.PRO.dbValue(), Instant.ofEpochSecond(renewalEpoch));
  }

  @Test
  void subscriptionUpdatedAnnualPriceFlipsBillingIntervalToAnnual() {
    long renewalEpoch = Instant.parse("2027-05-06T00:00:00Z").getEpochSecond();
    Subscription sub = stubSubscription("cus_xyz", "active", renewalEpoch, "price_pro_annual");

    Event event = stubEvent("evt_annual", "customer.subscription.updated", sub);
    when(eventLog.recordIfNew("evt_annual", "customer.subscription.updated")).thenReturn(true);
    when(customers.findOrgIdByCustomerId("cus_xyz")).thenReturn(Optional.of(42L));

    assertThat(service.handle(event)).isEqualTo(Outcome.PROCESSED);
    verify(customers).updateBillingInterval(42L, BillingInterval.ANNUAL.dbValue());
  }

  @Test
  void subscriptionUpdatedMonthlyPriceWritesMonthlyInterval() {
    Subscription sub = stubSubscription("cus_xyz", "active", 0L, "price_pro_monthly");

    Event event = stubEvent("evt_monthly", "customer.subscription.updated", sub);
    when(eventLog.recordIfNew("evt_monthly", "customer.subscription.updated")).thenReturn(true);
    when(customers.findOrgIdByCustomerId("cus_xyz")).thenReturn(Optional.of(42L));

    assertThat(service.handle(event)).isEqualTo(Outcome.PROCESSED);
    verify(customers).updateBillingInterval(42L, BillingInterval.MONTHLY.dbValue());
  }

  @Test
  void subscriptionUpdatedUnknownPriceFallsBackToMonthly() {
    // Orphaned price (env mismatch or manual-portal-edit on Stripe) shouldn't crash; defaults to
    // monthly so nobody loses access — same defensive default as PlanTier.fromDbValue.
    Subscription sub = stubSubscription("cus_xyz", "active", 0L, "price_unknown");

    Event event = stubEvent("evt_unknown", "customer.subscription.updated", sub);
    when(eventLog.recordIfNew("evt_unknown", "customer.subscription.updated")).thenReturn(true);
    when(customers.findOrgIdByCustomerId("cus_xyz")).thenReturn(Optional.of(42L));

    assertThat(service.handle(event)).isEqualTo(Outcome.PROCESSED);
    verify(customers).updateBillingInterval(42L, BillingInterval.MONTHLY.dbValue());
  }

  @Test
  void subscriptionUpdatedCanceledStatusDowngradesToFree() {
    Subscription sub = stubSubscription("cus_xyz", "canceled", null);

    Event event = stubEvent("evt_can", "customer.subscription.updated", sub);
    when(eventLog.recordIfNew("evt_can", "customer.subscription.updated")).thenReturn(true);
    when(customers.findOrgIdByCustomerId("cus_xyz")).thenReturn(Optional.of(42L));

    assertThat(service.handle(event)).isEqualTo(Outcome.PROCESSED);
    verify(customers).updatePlanAndRenewal(42L, PlanTier.FREE.dbValue(), null);
  }

  @Test
  void subscriptionUpdatedUnknownCustomerYieldsUnknownCustomerOutcome() {
    Subscription sub = stubSubscription("cus_orphan", "active", 0L);

    Event event = stubEvent("evt_orph", "customer.subscription.updated", sub);
    when(eventLog.recordIfNew("evt_orph", "customer.subscription.updated")).thenReturn(true);
    when(customers.findOrgIdByCustomerId("cus_orphan")).thenReturn(Optional.empty());

    assertThat(service.handle(event)).isEqualTo(Outcome.UNKNOWN_CUSTOMER);
    verify(customers, never()).updatePlanAndRenewal(anyLong(), anyString(), any());
  }

  @Test
  void subscriptionDeletedDowngradesToFree() {
    Subscription sub = stubSubscription("cus_xyz", "canceled", null);

    Event event = stubEvent("evt_del", "customer.subscription.deleted", sub);
    when(eventLog.recordIfNew("evt_del", "customer.subscription.deleted")).thenReturn(true);
    when(customers.findOrgIdByCustomerId("cus_xyz")).thenReturn(Optional.of(42L));

    assertThat(service.handle(event)).isEqualTo(Outcome.PROCESSED);
    verify(customers).updatePlanAndRenewal(42L, PlanTier.FREE.dbValue(), null);
  }

  @Test
  void paymentFailedOpensGraceWindow() {
    Invoice invoice = mock(Invoice.class);
    when(invoice.getCustomer()).thenReturn("cus_xyz");
    when(invoice.getId()).thenReturn("in_123");

    Event event = stubEvent("evt_pf", "invoice.payment_failed", invoice);
    when(eventLog.recordIfNew("evt_pf", "invoice.payment_failed")).thenReturn(true);
    when(customers.findOrgIdByCustomerId("cus_xyz")).thenReturn(Optional.of(42L));
    when(customers.openPaymentGrace(42L, NOW.plus(GRACE))).thenReturn(true);

    assertThat(service.handle(event)).isEqualTo(Outcome.PROCESSED);
    verify(customers).openPaymentGrace(42L, NOW.plus(GRACE));
    verify(customers, never()).updatePlanAndRenewal(anyLong(), anyString(), any());
  }

  @Test
  void paymentFailedDelegatesIdempotencyToTheRepository() {
    // Stripe Smart Retries fire repeated payment_failed events. The repo's conditional UPDATE
    // returns false the second time; the service must not treat that as an error or branch.
    Invoice invoice = mock(Invoice.class);
    when(invoice.getCustomer()).thenReturn("cus_xyz");
    when(invoice.getId()).thenReturn("in_456");

    Event event = stubEvent("evt_pf2", "invoice.payment_failed", invoice);
    when(eventLog.recordIfNew("evt_pf2", "invoice.payment_failed")).thenReturn(true);
    when(customers.findOrgIdByCustomerId("cus_xyz")).thenReturn(Optional.of(42L));
    when(customers.openPaymentGrace(42L, NOW.plus(GRACE))).thenReturn(false);

    assertThat(service.handle(event)).isEqualTo(Outcome.PROCESSED);
    verify(customers).openPaymentGrace(42L, NOW.plus(GRACE));
  }

  @Test
  void paymentFailedForUnknownCustomerYieldsUnknownCustomer() {
    Invoice invoice = mock(Invoice.class);
    when(invoice.getCustomer()).thenReturn("cus_orphan");

    Event event = stubEvent("evt_orphan_pf", "invoice.payment_failed", invoice);
    when(eventLog.recordIfNew("evt_orphan_pf", "invoice.payment_failed")).thenReturn(true);
    when(customers.findOrgIdByCustomerId("cus_orphan")).thenReturn(Optional.empty());

    assertThat(service.handle(event)).isEqualTo(Outcome.UNKNOWN_CUSTOMER);
    verify(customers, never()).openPaymentGrace(anyLong(), any());
  }

  @Test
  void paymentSucceededClearsGrace() {
    Invoice invoice = mock(Invoice.class);
    when(invoice.getCustomer()).thenReturn("cus_xyz");
    when(invoice.getId()).thenReturn("in_999");

    Event event = stubEvent("evt_ok", "invoice.payment_succeeded", invoice);
    when(eventLog.recordIfNew("evt_ok", "invoice.payment_succeeded")).thenReturn(true);
    when(customers.findOrgIdByCustomerId("cus_xyz")).thenReturn(Optional.of(42L));

    assertThat(service.handle(event)).isEqualTo(Outcome.PROCESSED);
    verify(customers).clearPaymentGrace(42L);
  }

  @Test
  void paymentSucceededForUnknownCustomerYieldsUnknownCustomer() {
    Invoice invoice = mock(Invoice.class);
    when(invoice.getCustomer()).thenReturn("cus_orphan");

    Event event = stubEvent("evt_orphan_ok", "invoice.payment_succeeded", invoice);
    when(eventLog.recordIfNew("evt_orphan_ok", "invoice.payment_succeeded")).thenReturn(true);
    when(customers.findOrgIdByCustomerId("cus_orphan")).thenReturn(Optional.empty());

    assertThat(service.handle(event)).isEqualTo(Outcome.UNKNOWN_CUSTOMER);
    verify(customers, never()).clearPaymentGrace(anyLong());
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static Event stubEvent(String id, String type, com.stripe.model.StripeObject data) {
    Event event = mock(Event.class);
    when(event.getId()).thenReturn(id);
    when(event.getType()).thenReturn(type);
    if (data != null) {
      EventDataObjectDeserializer deser = mock(EventDataObjectDeserializer.class);
      when(deser.getObject()).thenReturn(Optional.of(data));
      when(event.getDataObjectDeserializer()).thenReturn(deser);
    }
    return event;
  }

  private static Subscription stubSubscription(String customerId, String status, Long periodEnd) {
    return stubSubscription(customerId, status, periodEnd, null);
  }

  private static Subscription stubSubscription(
      String customerId, String status, Long periodEnd, String priceId) {
    Subscription sub = mock(Subscription.class);
    when(sub.getCustomer()).thenReturn(customerId);
    when(sub.getStatus()).thenReturn(status);
    if (periodEnd != null || priceId != null) {
      SubscriptionItem item = mock(SubscriptionItem.class);
      if (periodEnd != null) when(item.getCurrentPeriodEnd()).thenReturn(periodEnd);
      if (priceId != null) {
        Price price = mock(Price.class);
        when(price.getId()).thenReturn(priceId);
        when(item.getPrice()).thenReturn(price);
      }
      SubscriptionItemCollection items = mock(SubscriptionItemCollection.class);
      when(items.getData()).thenReturn(List.of(item));
      when(sub.getItems()).thenReturn(items);
    }
    return sub;
  }

  private static <T> T mock(Class<T> type) {
    return org.mockito.Mockito.mock(type);
  }

  private static long anyLong() {
    return org.mockito.ArgumentMatchers.anyLong();
  }

  private static String anyString() {
    return org.mockito.ArgumentMatchers.anyString();
  }
}
