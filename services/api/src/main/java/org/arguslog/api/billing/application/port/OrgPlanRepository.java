package org.arguslog.api.billing.application.port;

import java.time.Instant;
import java.util.Optional;
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
}
