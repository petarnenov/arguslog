package org.arguslog.worker.billing.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.arguslog.worker.billing.application.port.PlanExpiryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Hourly pass: for each Pro org whose one-time {@code plan_renews_at} has lapsed without a
 * follow-up purchase extending it, opens a payment-grace window. Re-uses the existing grace
 * mechanism + downgrade job — semantically identical to "Stripe payment_failed" but driven by
 * time rather than an external event, since one-time crypto + LS purchases never auto-renew.
 */
@Service
public class PlanExpiryService {

  private static final Logger log = LoggerFactory.getLogger(PlanExpiryService.class);

  private final PlanExpiryRepository repository;
  private final Clock clock;
  private final Duration gracePeriod;

  public PlanExpiryService(
      PlanExpiryRepository repository,
      Clock clock,
      @Value("${arguslog.billing.grace-period:P7D}") Duration gracePeriod) {
    this.repository = repository;
    this.clock = clock;
    this.gracePeriod = gracePeriod;
  }

  public List<Long> runOnce() {
    List<Long> opened =
        repository.openGraceForExpiredPlans(clock.instant(), gracePeriod.toSeconds());
    if (opened.isEmpty()) {
      log.debug("plan-expiry pass found nothing to do");
    } else {
      log.warn(
          "plan-expiry pass opened grace for {} org(s): {} — downgrade follows in {}",
          opened.size(),
          opened,
          gracePeriod);
    }
    return opened;
  }
}
