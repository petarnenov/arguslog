package org.arguslog.api.billing.domain;

import java.time.Duration;
import java.util.Locale;

/**
 * Single source of truth for per-plan limits and pricing. The DB column {@code
 * organizations.plan} stores the wire string ({@code "free"}, {@code "pro"}, {@code
 * "enterprise"}); this enum mirrors it with the actual numeric caps the rest of the app reads.
 *
 * <p>Pricing model: PRO is sold as one-time prepaid bundles for 1, 3, 6, or 12 months with an
 * aggressive discount ladder. The {@link #monthlyPriceCents()} field is kept for backward
 * compatibility with the existing usage snapshot (frontend reads it as the "base monthly rate"
 * shown next to the plan name); {@link #priceCentsForDuration(int)} is the canonical price source
 * for new checkout flows.
 *
 * <p>Anything that needs a numeric limit — quota enforcer, dashboard banner, billing page CTA —
 * comes here, NOT to the DB column. Bumping a tier's allowance is one edit + a deploy, not a
 * migration.
 */
public enum PlanTier {
  FREE(0, 5_000L, 1, Duration.ofDays(30)),
  PRO(1199, 100_000L, 10, Duration.ofDays(30)),
  ENTERPRISE(0, Long.MAX_VALUE, Integer.MAX_VALUE, Duration.ofDays(365));

  private final int monthlyPriceCents;
  private final long monthlyEventCap;
  private final int projectCap;
  private final Duration retention;

  PlanTier(int monthlyPriceCents, long monthlyEventCap, int projectCap, Duration retention) {
    this.monthlyPriceCents = monthlyPriceCents;
    this.monthlyEventCap = monthlyEventCap;
    this.projectCap = projectCap;
    this.retention = retention;
  }

  public String dbValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public int monthlyPriceCents() {
    return monthlyPriceCents;
  }

  public long monthlyEventCap() {
    return monthlyEventCap;
  }

  public int projectCap() {
    return projectCap;
  }

  public Duration retention() {
    return retention;
  }

  /**
   * Total price in cents for a one-time purchase covering {@code months}. PRO uses an aggressive
   * ladder ($11.99 / $29.99 / $53.99 / $95.99 for 1/3/6/12 months — 0% / 17% / 25% / 33% discount
   * versus the base rate). Other tiers return 0; FREE and ENTERPRISE are not sold via the
   * checkout flow.
   */
  public int priceCentsForDuration(int months) {
    if (this != PRO) return 0;
    return switch (months) {
      case 1 -> 1199;
      case 3 -> 2999;
      case 6 -> 5399;
      case 12 -> 9599;
      default -> throw new IllegalArgumentException(
          "Unsupported duration for "
              + name()
              + ": "
              + months
              + " months. Allowed: 1, 3, 6, 12.");
    };
  }

  public static PlanTier fromDbValue(String raw) {
    if (raw == null) return FREE;
    try {
      return PlanTier.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return FREE;
    }
  }
}
