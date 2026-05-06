package org.arguslog.api.billing.application;

import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import java.time.Instant;
import java.util.Optional;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.arguslog.api.billing.application.port.StripeEventLog;
import org.arguslog.api.billing.domain.PlanTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Routes verified Stripe webhook events to plan-state mutations.
 *
 * <p>Idempotency is enforced top-of-handler via {@link StripeEventLog#recordIfNew}. The check is an
 * optimization — every individual mutation is also idempotent (UPSERT-style updates), so a Stripe
 * redelivery during a partial failure still converges to the right state.
 *
 * <p>Events handled:
 *
 * <ul>
 *   <li>{@code checkout.session.completed} — customer created, subscription started → bind customer
 *       id to org, set plan = pro, derive plan_renews_at from the new subscription
 *   <li>{@code customer.subscription.updated} — refresh plan_renews_at, downgrade if status
 *       transitions to canceled / unpaid / incomplete_expired
 *   <li>{@code customer.subscription.deleted} — full cancellation → plan = free, no renewal
 *   <li>{@code invoice.payment_failed} — log; the dashboard banner from {@code plan_renews_at}
 *       expiring is the visible signal. Auto-downgrade after grace period is P5.
 * </ul>
 */
@Service
public class StripeWebhookService implements StripeWebhookUseCase {

  private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

  private final StripeEventLog eventLog;
  private final BillingCustomerRepository customers;

  public StripeWebhookService(StripeEventLog eventLog, BillingCustomerRepository customers) {
    this.eventLog = eventLog;
    this.customers = customers;
  }

  @Override
  @Transactional
  public Outcome handle(Event event) {
    String eventId = event.getId();
    String type = event.getType();
    if (!eventLog.recordIfNew(eventId, type)) {
      log.debug("stripe event {} ({}) already seen, skipping", eventId, type);
      return Outcome.ALREADY_SEEN;
    }

    return switch (type) {
      case "checkout.session.completed" -> handleCheckoutCompleted(event);
      case "customer.subscription.updated" -> handleSubscriptionUpdated(event);
      case "customer.subscription.deleted" -> handleSubscriptionDeleted(event);
      case "invoice.payment_failed" -> handlePaymentFailed(event);
      default -> {
        log.debug("ignoring unhandled stripe event type {}", type);
        yield Outcome.IGNORED;
      }
    };
  }

  private Outcome handleCheckoutCompleted(Event event) {
    Session session = (Session) deserialize(event);
    if (session == null) return Outcome.IGNORED;
    String reference = session.getClientReferenceId();
    String customerId = session.getCustomer();
    if (reference == null || customerId == null) {
      log.warn(
          "checkout.session.completed missing client_reference_id or customer (event {})",
          event.getId());
      return Outcome.IGNORED;
    }
    long orgId;
    try {
      orgId = Long.parseLong(reference);
    } catch (NumberFormatException e) {
      log.warn("client_reference_id {} is not a long; skipping", reference);
      return Outcome.IGNORED;
    }

    customers.saveCustomerId(orgId, customerId);
    // Renewal date isn't on the Session object — the matching subscription.updated event arrives
    // straight after with current_period_end. Set the plan now so the dashboard reflects the
    // upgrade immediately; renewal date follows in the next event.
    customers.updatePlanAndRenewal(orgId, PlanTier.PRO.dbValue(), null);
    log.info("checkout completed: org {} now on Pro (customer {})", orgId, customerId);
    return Outcome.PROCESSED;
  }

  private Outcome handleSubscriptionUpdated(Event event) {
    Subscription sub = (Subscription) deserialize(event);
    if (sub == null) return Outcome.IGNORED;
    Optional<Long> orgId = customers.findOrgIdByCustomerId(sub.getCustomer());
    if (orgId.isEmpty()) {
      log.warn(
          "subscription.updated for unknown customer {} (event {})",
          sub.getCustomer(),
          event.getId());
      return Outcome.UNKNOWN_CUSTOMER;
    }

    String planValue =
        switch (sub.getStatus()) {
          case "active", "trialing", "past_due" -> PlanTier.PRO.dbValue();
          // canceled / unpaid / incomplete_expired → revoke premium access
          default -> PlanTier.FREE.dbValue();
        };
    // Stripe 2025-03 API moved current_period_end off Subscription onto its line items; pick the
    // first item's renewal as the org's. We bill a single line per subscription so this is the
    // org-wide value.
    Instant renewsAt =
        Optional.ofNullable(sub.getItems())
            .map(items -> items.getData())
            .filter(list -> !list.isEmpty())
            .map(list -> list.get(0).getCurrentPeriodEnd())
            .map(Instant::ofEpochSecond)
            .orElse(null);
    customers.updatePlanAndRenewal(orgId.get(), planValue, renewsAt);
    log.info(
        "subscription updated: org {} status={} plan={} renews={}",
        orgId.get(),
        sub.getStatus(),
        planValue,
        renewsAt);
    return Outcome.PROCESSED;
  }

  private Outcome handleSubscriptionDeleted(Event event) {
    Subscription sub = (Subscription) deserialize(event);
    if (sub == null) return Outcome.IGNORED;
    Optional<Long> orgId = customers.findOrgIdByCustomerId(sub.getCustomer());
    if (orgId.isEmpty()) {
      log.warn("subscription.deleted for unknown customer {}", sub.getCustomer());
      return Outcome.UNKNOWN_CUSTOMER;
    }
    customers.updatePlanAndRenewal(orgId.get(), PlanTier.FREE.dbValue(), null);
    log.info("subscription deleted: org {} downgraded to Free", orgId.get());
    return Outcome.PROCESSED;
  }

  private Outcome handlePaymentFailed(Event event) {
    Invoice invoice = (Invoice) deserialize(event);
    if (invoice == null) return Outcome.IGNORED;
    Optional<Long> orgId = customers.findOrgIdByCustomerId(invoice.getCustomer());
    if (orgId.isEmpty()) {
      log.warn("invoice.payment_failed for unknown customer {}", invoice.getCustomer());
      return Outcome.UNKNOWN_CUSTOMER;
    }
    // P4: visible only via the dashboard banner once plan_renews_at lapses. Auto-downgrade
    // after a grace window (3 days?) is a P5 polish task.
    log.warn(
        "payment failed for org {} (invoice {}) — banner will follow once renewal lapses",
        orgId.get(),
        invoice.getId());
    return Outcome.PROCESSED;
  }

  private static StripeObject deserialize(Event event) {
    return event.getDataObjectDeserializer().getObject().orElse(null);
  }
}
