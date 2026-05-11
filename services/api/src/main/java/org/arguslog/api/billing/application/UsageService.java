package org.arguslog.api.billing.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.billing.application.port.UsageRepository;
import org.arguslog.api.billing.domain.BillingInterval;
import org.arguslog.billing.PlanTier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsageService implements UsageUseCase {

  private final OrgPlanRepository plans;
  private final UsageRepository usage;
  private final Clock clock;

  public UsageService(OrgPlanRepository plans, UsageRepository usage, Clock clock) {
    this.plans = plans;
    this.usage = usage;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UsageSnapshot> snapshot(long orgId) {
    Optional<PlanTier> plan = plans.findPlan(orgId);
    if (plan.isEmpty()) return Optional.empty();
    PlanTier tier = plan.get();
    long used = usage.currentEventCount(orgId, periodStartUtc());
    long cap = tier.monthlyEventCap();
    double ratio = cap == 0 ? 1.0 : (double) used / (double) cap;
    boolean exceeded = used >= cap;
    var graceUntil = plans.findPaymentGraceUntil(orgId).orElse(null);
    BillingInterval interval = plans.findBillingInterval(orgId).orElse(BillingInterval.MONTHLY);
    var renewsAt = plans.findRenewsAt(orgId).orElse(null);
    Bonus bonus =
        plans
            .findActiveBonus(orgId)
            .map(b -> new Bonus(b.until(), b.reason(), b.grantedByEmail()))
            .orElse(null);
    return Optional.of(
        new UsageSnapshot(tier, used, cap, ratio, exceeded, graceUntil, interval, renewsAt, bonus));
  }

  private LocalDate periodStartUtc() {
    return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
  }
}
