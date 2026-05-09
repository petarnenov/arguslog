package org.arguslog.worker.billing.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly cron entry point. Default cadence is once an hour, on the hour — the lapse detection is
 * cheap and customers expect their dashboard to flip into "payment failed" promptly after their
 * plan timer hits zero.
 */
@Component
public class PlanExpiryJob {

  private final PlanExpiryService service;

  public PlanExpiryJob(PlanExpiryService service) {
    this.service = service;
  }

  @Scheduled(cron = "${arguslog.billing.expiry-cron:0 5 * * * *}", zone = "UTC")
  public void run() {
    service.runOnce();
  }
}
