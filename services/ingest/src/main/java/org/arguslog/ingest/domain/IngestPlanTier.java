package org.arguslog.ingest.domain;

import java.util.Locale;

/**
 * Mirror of api's {@code PlanTier} — the monthly event cap is the only field ingest needs to
 * enforce quota at the tier boundary. Wire string ({@code "free"}, {@code "starter"}, {@code
 * "pro"}, {@code "business"}, {@code "enterprise"}) matches the {@code organizations.plan} enum
 * so a single column maps to a tier in either service.
 *
 * <p>TODO(P5): extract this and the api copy to a shared module — drift between the two would
 * cause "quota exceeded at api but allowed at ingest" (or vice versa) which is a hard bug to
 * notice in dashboards.
 */
public enum IngestPlanTier {
  FREE(5_000L),
  STARTER(25_000L),
  PRO(100_000L),
  BUSINESS(Long.MAX_VALUE),
  ENTERPRISE(Long.MAX_VALUE);

  private final long monthlyEventCap;

  IngestPlanTier(long monthlyEventCap) {
    this.monthlyEventCap = monthlyEventCap;
  }

  public long monthlyEventCap() {
    return monthlyEventCap;
  }

  /**
   * Maps the wire/DB string to a tier. Unknown values fall back to {@link #FREE} so a stray row
   * never opens the floodgates — most restrictive default is the safer pick.
   */
  public static IngestPlanTier fromDbValue(String raw) {
    if (raw == null) return FREE;
    try {
      return IngestPlanTier.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return FREE;
    }
  }
}
