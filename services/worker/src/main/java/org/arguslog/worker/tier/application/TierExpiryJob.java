package org.arguslog.worker.tier.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron entry point for the tier downgrade pass. Default cadence (04:00 UTC) intentionally sits an
 * hour after the retention job at 03:00 so neither one is racing the other for connection-pool
 * slots on a small Railway box.
 */
@Component
public class TierExpiryJob {

  private final TierExpiryService service;

  public TierExpiryJob(TierExpiryService service) {
    this.service = service;
  }

  @Scheduled(cron = "${arguslog.tier.expiry-cron:0 0 4 * * *}", zone = "UTC")
  public void run() {
    service.runOnce();
  }
}
