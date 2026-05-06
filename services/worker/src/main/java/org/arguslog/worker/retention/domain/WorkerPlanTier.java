package org.arguslog.worker.retention.domain;

import java.time.Duration;
import java.util.Locale;

/**
 * Mirror of api's {@code PlanTier} — only the retention duration is consulted by the worker. Wire
 * string ({@code "free"}, {@code "pro"}, {@code "enterprise"}) matches the {@code
 * organizations.plan} enum so a single column maps to a tier.
 *
 * <p>TODO(P6): extract this and the api/ingest copies to a shared module — drift between the three
 * would cause "data deleted at 30d here, kept at 90d there" which is hard to notice in dashboards.
 */
public enum WorkerPlanTier {
  FREE(Duration.ofDays(30)),
  PRO(Duration.ofDays(30)),
  ENTERPRISE(Duration.ofDays(365));

  private final Duration retention;

  WorkerPlanTier(Duration retention) {
    this.retention = retention;
  }

  public Duration retention() {
    return retention;
  }

  /**
   * Maps the wire/DB string to a tier. Unknown values fall back to {@link #FREE} so a stray row
   * deletes data sooner rather than keeping it forever — over-deletion is recoverable from backups;
   * under-deletion is a compliance breach.
   */
  public static WorkerPlanTier fromDbValue(String raw) {
    if (raw == null) return FREE;
    try {
      return WorkerPlanTier.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return FREE;
    }
  }
}
