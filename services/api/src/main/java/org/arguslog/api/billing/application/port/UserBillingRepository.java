package org.arguslog.api.billing.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.billing.application.port.OrgPlanRepository.BonusSnapshot;
import org.arguslog.api.billing.domain.BillingInterval;
import org.arguslog.api.billing.domain.PlanTier;

/**
 * Read-side port for "what's this user's billing state". The new source of truth for plan
 * resolution (Phase 2 of the per-user billing migration). Org-level callers find the org's
 * owner via membership and then ask this port — see {@link OrgPlanRepository#findPlan(long)}
 * which delegates here so existing callers keep working unchanged during the transition.
 */
public interface UserBillingRepository {

  Optional<PlanTier> findPlan(UUID userId);

  /** Active payment grace deadline opened by a {@code invoice.payment_failed} webhook. */
  Optional<Instant> findPaymentGraceUntil(UUID userId);

  /** Monthly / annual / multi-month cadence. Free-tier users return {@link BillingInterval#MONTHLY}. */
  Optional<BillingInterval> findBillingInterval(UUID userId);

  /** Next renewal/expiry timestamp from {@code users.plan_renews_at}. */
  Optional<Instant> findRenewsAt(UUID userId);

  /**
   * Active bonus snapshot for {@code userId} when an admin has comp'd them. Empty when no grant
   * is active. The plan column is the source of truth for caps; this read is dashboard metadata.
   */
  Optional<BonusSnapshot> findActiveBonus(UUID userId);
}
