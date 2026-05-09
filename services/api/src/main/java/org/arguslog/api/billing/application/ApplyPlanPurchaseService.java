package org.arguslog.api.billing.application;

import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Optional;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.billing.application.port.PlanPurchaseRepository;
import org.arguslog.api.billing.domain.BillingInterval;
import org.arguslog.api.billing.domain.BillingProvider;
import org.arguslog.api.billing.domain.PlanPurchase;
import org.arguslog.api.billing.domain.PlanTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplyPlanPurchaseService implements ApplyPlanPurchaseUseCase {

  private static final Logger log = LoggerFactory.getLogger(ApplyPlanPurchaseService.class);

  private final PlanPurchaseRepository purchases;
  private final BillingCustomerRepository customers;
  private final OrgPlanRepository plans;
  private final Clock clock;

  public ApplyPlanPurchaseService(
      PlanPurchaseRepository purchases,
      BillingCustomerRepository customers,
      OrgPlanRepository plans,
      Clock clock) {
    this.purchases = purchases;
    this.customers = customers;
    this.plans = plans;
    this.clock = clock;
  }

  @Override
  @Transactional
  public PlanPurchase apply(
      long orgId,
      BillingProvider provider,
      String providerReference,
      PlanTier plan,
      int durationMonths,
      int amountCents,
      String currency,
      Optional<String> payCurrency) {
    Instant now = clock.instant();
    Instant currentRenewsAt = plans.findRenewsAt(orgId).orElse(now);
    Instant baseline = currentRenewsAt.isAfter(now) ? currentRenewsAt : now;
    Instant newExpiresAt =
        baseline.atOffset(ZoneOffset.UTC).plus(Period.ofMonths(durationMonths)).toInstant();

    PlanPurchase purchase =
        purchases.recordIfNew(
            orgId,
            provider,
            providerReference,
            plan,
            durationMonths,
            amountCents,
            currency,
            payCurrency,
            newExpiresAt);

    if (!purchase.providerReference().equals(providerReference)
        || purchase.amountCents() != amountCents) {
      // Should not happen — recordIfNew returns the row keyed on (provider, providerReference);
      // but guard against the surprising case so we never silently extend on a logic bug.
      log.error(
          "plan purchase recordIfNew returned mismatched row for {}/{}",
          provider,
          providerReference);
      return purchase;
    }

    boolean isNewlyApplied = !purchase.appliedAt().isBefore(now.minusSeconds(5));
    if (!isNewlyApplied) {
      log.info(
          "plan purchase {}/{} already applied at {} — skipping plan/renewal update",
          provider,
          providerReference,
          purchase.appliedAt());
      return purchase;
    }

    customers.updatePlanAndRenewal(orgId, plan.dbValue(), purchase.expiresAt());
    customers.updateBillingInterval(orgId, BillingInterval.fromMonths(durationMonths).dbValue());
    customers.clearPaymentGrace(orgId);

    log.info(
        "plan purchase applied: org {} via {} ref={} plan={} months={} amount={}c expires={}",
        orgId,
        provider,
        providerReference,
        plan.dbValue(),
        durationMonths,
        amountCents,
        purchase.expiresAt());
    return purchase;
  }
}
