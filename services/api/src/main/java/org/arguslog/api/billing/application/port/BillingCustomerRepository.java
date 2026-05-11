package org.arguslog.api.billing.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Read + write port for the Stripe-side metadata an org carries — {@code stripe_customer_id} and
 * {@code plan_renews_at}. The plan column itself is mutated via a separate {@code
 * OrgPlanRepository} write path (P4 #5) so reads stay focused.
 */
public interface BillingCustomerRepository {

  Optional<String> findCustomerId(long orgId);

  /**
   * Per-user lookup (V26+). The user is the source of truth for billing identity; checkout reuses
   * this customer instead of spinning up a new Stripe customer for every owned org. Empty for
   * users who have never reached checkout.
   */
  Optional<String> findCustomerIdForUser(UUID userId);

  /**
   * Reverse lookup — webhook events arrive with the Stripe customer id, not our org id, so the
   * handler resolves which row to mutate via this. Returns empty when Stripe sends an event for a
   * customer that was never created through our checkout (e.g. someone added the same customer to a
   * different api environment by hand).
   */
  Optional<Long> findOrgIdByCustomerId(String customerId);

  /**
   * Per-user reverse lookup (V26+). Used by the webhook handler once it switches to user-primary
   * writes — resolves "which user does this Stripe customer belong to" without going through the
   * org indirection.
   */
  Optional<UUID> findUserIdByCustomerId(String customerId);

  /**
   * Persists the Stripe customer id created by {@code checkout.session.completed}. Idempotent —
   * called from the webhook handler which Stripe redelivers on 5xx.
   */
  void saveCustomerId(long orgId, String customerId);

  /** Updates plan + renewal timestamp in one shot. Used by the subscription webhook handlers. */
  void updatePlanAndRenewal(long orgId, String planDbValue, Instant renewsAt);

  /**
   * Persists the billing cadence (monthly / annual) the org is on. Called from {@code
   * checkout.session.completed} and {@code customer.subscription.updated}, where the chosen Stripe
   * Price tells us which cadence the customer just committed to. Idempotent.
   */
  void updateBillingInterval(long orgId, String intervalDbValue);

  /**
   * Opens a payment grace window. The write is conditional — only takes effect when no grace is
   * currently open or the previous one already lapsed, so Stripe Smart Retries (which fire repeated
   * {@code invoice.payment_failed} events over ~4 weeks) cannot keep extending the window past the
   * first failure. Returns {@code true} when the row was updated.
   */
  boolean openPaymentGrace(long orgId, Instant graceUntil);

  /**
   * Clears any active payment grace window. Called by the {@code invoice.payment_succeeded} webhook
   * so a customer who re-uploads a working card immediately stops seeing the banner.
   */
  void clearPaymentGrace(long orgId);
}
