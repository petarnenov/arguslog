package org.arguslog.billing;

import java.time.Duration;
import java.util.Locale;

/**
 * Single source of truth for per-tier limits in the OSS Arguslog distribution. The DB column {@code
 * users.tier} (renamed from {@code users.plan} in V30) stores the wire string ({@code "regular"},
 * {@code "silver"}, {@code "gold"}, {@code "platinum"}); this enum mirrors it with the numeric caps
 * the rest of the platform reads.
 *
 * <p>Lives in {@code :lib:plan-tier} so api / ingest / worker all import the same definition — caps
 * cannot drift between services. There is no payment / pricing surface any more: tier elevation
 * happens via admin grant, recorded with an optional {@code tier_expires_at}; on expiry the worker
 * downgrades the user back to {@link #REGULAR}.
 *
 * <p>Anything that needs a numeric limit — quota enforcer, dashboard banner, project create
 * endpoint, /me/tier response — comes here, NOT to the DB column. Bumping a tier's allowance is one
 * edit + a deploy, not a migration.
 */
public enum PlanTier {
  REGULAR(5_000L, 1, 1, 1, Duration.ofDays(30)),
  SILVER(25_000L, 3, 3, 3, Duration.ofDays(30)),
  GOLD(100_000L, 10, 10, 10, Duration.ofDays(90)),
  PLATINUM(
      Long.MAX_VALUE,
      Integer.MAX_VALUE,
      Integer.MAX_VALUE,
      Integer.MAX_VALUE,
      Duration.ofDays(365));

  private final long monthlyEventCap;
  private final int projectCap;
  private final int memberCap;
  private final int orgCap;
  private final Duration retention;

  PlanTier(long monthlyEventCap, int projectCap, int memberCap, int orgCap, Duration retention) {
    this.monthlyEventCap = monthlyEventCap;
    this.projectCap = projectCap;
    this.memberCap = memberCap;
    this.orgCap = orgCap;
    this.retention = retention;
  }

  public String dbValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public long monthlyEventCap() {
    return monthlyEventCap;
  }

  public int projectCap() {
    return projectCap;
  }

  public int memberCap() {
    return memberCap;
  }

  public int orgCap() {
    return orgCap;
  }

  public Duration retention() {
    return retention;
  }

  /**
   * Maps the wire/DB string to a tier. Unknown / null values fall back to {@link #REGULAR} so a
   * stray row never opens the floodgates (ingest) or keeps data forever (worker retention) — most
   * restrictive default is the safer pick.
   */
  public static PlanTier fromDbValue(String raw) {
    if (raw == null) return REGULAR;
    try {
      return PlanTier.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return REGULAR;
    }
  }
}
