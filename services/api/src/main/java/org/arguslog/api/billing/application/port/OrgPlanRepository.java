package org.arguslog.api.billing.application.port;

import java.time.Instant;
import java.util.Optional;
import org.arguslog.api.billing.domain.BillingInterval;
import org.arguslog.api.billing.domain.PlanTier;

/**
 * Tiny read-side port for "what's this org currently subscribed to". Lives in the billing module
 * because the rest of the api treats {@code organizations.plan} as opaque metadata; only the
 * billing path needs to map the wire string to a {@link PlanTier} with its caps.
 */
public interface OrgPlanRepository {

  Optional<PlanTier> findPlan(long orgId);

  /**
   * Returns the active payment grace deadline if a {@code invoice.payment_failed} webhook opened
   * one. {@link Optional#empty()} means no grace is in effect (most orgs, most of the time).
   */
  Optional<Instant> findPaymentGraceUntil(long orgId);

  /**
   * Returns the org's current billing cadence (monthly / annual). Empty when the org does not
   * exist; defaults to {@link BillingInterval#MONTHLY} for free-tier orgs that never went through
   * checkout.
   */
  Optional<BillingInterval> findBillingInterval(long orgId);

  /**
   * Returns the next renewal/expiry timestamp from {@code organizations.plan_renews_at}. Empty for
   * free-tier orgs and during the brief window between {@code checkout.session.completed} and the
   * first {@code customer.subscription.updated} event that carries the period end.
   */
  Optional<Instant> findRenewsAt(long orgId);
}
