package org.arguslog.api.billing.domain;

import java.time.Duration;
import java.util.Locale;

/**
 * Single source of truth for per-plan limits. The DB column {@code organizations.plan} stores the
 * wire string ({@code "free"}, {@code "pro"}, {@code "enterprise"}); this enum mirrors it with the
 * actual numeric caps the rest of the app reads.
 *
 * <p>Anything that needs to know "what's the limit" — quota enforcer, dashboard banner, billing
 * page CTA — comes here, NOT to the column. That way bumping a tier's allowance is one edit + a
 * deploy, not a migration.
 *
 * <p>Pricing lives next to the cap to keep the dashboard coherent with the api. Stripe price IDs
 * stay in env so test + prod can target different price objects without code changes.
 */
public enum PlanTier {
  FREE(0, 5_000L, 1, Duration.ofDays(30)),
  PRO(900, 100_000L, 10, Duration.ofDays(30)), // $9.00 — Stripe expects integer cents
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

  /** Wire string used in the DB enum + the api responses. */
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
   * Parses the wire/DB string into a tier. Unknown values fall back to {@link #FREE} so a stray row
   * never crashes a controller — the worst case is the most restrictive limits, which is the safer
   * default.
   */
  public static PlanTier fromDbValue(String raw) {
    if (raw == null) return FREE;
    try {
      return PlanTier.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return FREE;
    }
  }
}
