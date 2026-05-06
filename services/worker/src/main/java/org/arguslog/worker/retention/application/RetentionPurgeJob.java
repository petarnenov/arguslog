package org.arguslog.worker.retention.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron entry point for the retention purge. Cadence comes from {@code arguslog.retention.cron}
 * (default: 03:00 UTC daily) so ops can shift it without a redeploy.
 *
 * <p>Kept thin on purpose — the job class only delegates so {@link RetentionPurgeService} stays
 * unit-testable without dragging in Spring scheduling.
 */
@Component
public class RetentionPurgeJob {

  private final RetentionPurgeService service;

  public RetentionPurgeJob(RetentionPurgeService service) {
    this.service = service;
  }

  @Scheduled(cron = "${arguslog.retention.cron:0 0 3 * * *}", zone = "UTC")
  public void run() {
    service.runOnce();
  }
}
