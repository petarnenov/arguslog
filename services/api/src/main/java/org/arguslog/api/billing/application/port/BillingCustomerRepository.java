package org.arguslog.api.billing.application.port;

import java.time.Instant;
import java.util.Optional;

/**
 * Read + write port for the Stripe-side metadata an org carries — {@code stripe_customer_id} and
 * {@code plan_renews_at}. The plan column itself is mutated via a separate {@code
 * OrgPlanRepository} write path (P4 #5) so reads stay focused.
 */
public interface BillingCustomerRepository {

  Optional<String> findCustomerId(long orgId);

  /**
   * Persists the Stripe customer id created by {@code checkout.session.completed}. Idempotent —
   * called from the webhook handler which Stripe redelivers on 5xx.
   */
  void saveCustomerId(long orgId, String customerId);

  /** Updates plan + renewal timestamp in one shot. Used by the subscription webhook handlers. */
  void updatePlanAndRenewal(long orgId, String planDbValue, Instant renewsAt);
}
