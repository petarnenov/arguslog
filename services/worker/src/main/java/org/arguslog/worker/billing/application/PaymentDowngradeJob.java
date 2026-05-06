package org.arguslog.worker.billing.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron entry point. Default cadence (04:00 UTC) intentionally sits an hour after the retention job
 * at 03:00 — keeps daily background work staggered so neither one is racing the other for
 * connection-pool slots on a tiny Railway box.
 */
@Component
public class PaymentDowngradeJob {

  private final PaymentDowngradeService service;

  public PaymentDowngradeJob(PaymentDowngradeService service) {
    this.service = service;
  }

  @Scheduled(cron = "${arguslog.billing.downgrade-cron:0 0 4 * * *}", zone = "UTC")
  public void run() {
    service.runOnce();
  }
}
