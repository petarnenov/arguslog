package org.arguslog.worker.billing.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily reminder pass. Default cadence (09:00 UTC) is the global "billable office hour" — picks up
 * customers in EU + Americas without burning email volume in someone's middle of the night.
 */
@Component
public class RenewalReminderJob {

  private final RenewalReminderService service;

  public RenewalReminderJob(RenewalReminderService service) {
    this.service = service;
  }

  @Scheduled(cron = "${arguslog.billing.reminder-cron:0 0 9 * * *}", zone = "UTC")
  public void run() {
    service.runOnce();
  }
}
