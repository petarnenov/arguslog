package org.arguslog.billing;

import java.time.Duration;
import java.util.Locale;

/**
 * Single source of truth for per-plan limits and pricing. The DB column {@code users.plan}
 * (post-V27; previously {@code organizations.plan}) stores the wire string ({@code "free"}, {@code
 * "starter"}, {@code "pro"}, {@code "business"}, {@code "enterprise"}); this enum mirrors it with
 * the actual numeric caps the rest of the platform reads.
 *
 * <p>Lives in {@code :lib:plan-tier} so api / ingest / worker all import the same definition — caps
 * cannot drift between services. Each paid tier is sold as one-time prepaid bundles for 1, 3, 6, or
 * 12 months with an aggressive 0% / 17% / 25% / 33% discount ladder against the monthly base.
 * {@link #priceCentsForDuration(int)} is the canonical price source for new checkout flows; {@link
 * #monthlyPriceCents()} is kept as the per-tier "headline" rate the dashboard renders.
 *
 * <p>Anything that needs a numeric limit — quota enforcer, dashboard banner, project create
 * endpoint, billing page — comes here, NOT to the DB column. Bumping a tier's allowance is one edit
 * + a deploy, not a migration.
 */
public enum PlanTier {
  FREE(0, 5_000L, 1, 1, 1, Duration.ofDays(30)),
  STARTER(1199, 25_000L, 3, 3, 3, Duration.ofDays(30)),
  PRO(2999, 100_000L, 10, 10, 10, Duration.ofDays(90)),
  BUSINESS(
      7999,
      Long.MAX_VALUE,
      Integer.MAX_VALUE,
      Integer.MAX_VALUE,
      Integer.MAX_VALUE,
      Duration.ofDays(365)),
  ENTERPRISE(
      0,
      Long.MAX_VALUE,
      Integer.MAX_VALUE,
      Integer.MAX_VALUE,
      Integer.MAX_VALUE,
      Duration.ofDays(365));

  private final int monthlyPriceCents;
  private final long monthlyEventCap;
  private final int projectCap;
  private final int memberCap;
  private final int orgCap;
  private final Duration retention;

  PlanTier(
      int monthlyPriceCents,
      long monthlyEventCap,
      int projectCap,
      int memberCap,
      int orgCap,
      Duration retention) {
    this.monthlyPriceCents = monthlyPriceCents;
    this.monthlyEventCap = monthlyEventCap;
    this.projectCap = projectCap;
    this.memberCap = memberCap;
    this.orgCap = orgCap;
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

  public int memberCap() {
    return memberCap;
  }

  /**
   * Maximum number of organizations a user can <b>own</b> across the platform when their highest
   * plan is this tier. This is a per-user cap (not per-org), enforced in {@code OrgService.create}
   * — it stops a single user from spinning up dozens of free orgs to multiply free-tier quotas.
   * Members invited into other people's orgs are unaffected; only the owner role counts.
   */
  public int orgCap() {
    return orgCap;
  }

  public Duration retention() {
    return retention;
  }

  /** True for tiers customers buy via the self-serve flow (Starter / Pro / Business). */
  public boolean isPaid() {
    return this == STARTER || this == PRO || this == BUSINESS;
  }

  /**
   * Total price in cents for a one-time purchase covering {@code months}. Each paid tier follows
   * the same 0% / 17% / 25% / 33% ladder relative to its monthly base ($11.99 / $29.99 / $79.99).
   * {@link #FREE} and {@link #ENTERPRISE} are not sold via the self-serve flow and return 0.
   */
  public int priceCentsForDuration(int months) {
    if (!isPaid()) return 0;
    return switch (this) {
      case STARTER ->
          switch (months) {
            case 1 -> 1199;
            case 3 -> 2999;
            case 6 -> 5399;
            case 12 -> 9599;
            default -> throw unsupportedMonths(months);
          };
      case PRO ->
          switch (months) {
            case 1 -> 2999;
            case 3 -> 7499;
            case 6 -> 13499;
            case 12 -> 23999;
            default -> throw unsupportedMonths(months);
          };
      case BUSINESS ->
          switch (months) {
            case 1 -> 7999;
            case 3 -> 19999;
            case 6 -> 35999;
            case 12 -> 63999;
            default -> throw unsupportedMonths(months);
          };
      default -> 0;
    };
  }

  private IllegalArgumentException unsupportedMonths(int months) {
    return new IllegalArgumentException(
        "Unsupported duration for " + name() + ": " + months + " months. Allowed: 1, 3, 6, 12.");
  }

  /**
   * Maps the wire/DB string to a tier. Unknown / null values fall back to {@link #FREE} so a stray
   * row never opens the floodgates (ingest) or keeps data forever (worker retention) — most
   * restrictive default is the safer pick.
   *
   * <p>OSS conversion Phase 1 shim: also accepts the new color-themed tier names
   * (regular/silver/gold/platinum) introduced by V28. Mapping mirrors the cap structure —
   * regular↔FREE, silver↔STARTER, gold↔PRO, platinum↔BUSINESS — so a row backfilled to the new
   * spelling reads back as the same Java enum constant during the transition window. Phase 2
   * renames the enum constants themselves and removes this shim.
   */
  public static PlanTier fromDbValue(String raw) {
    if (raw == null) return FREE;
    String normalized = raw.toLowerCase(Locale.ROOT);
    PlanTier aliased = aliasOrNull(normalized);
    if (aliased != null) return aliased;
    try {
      return PlanTier.valueOf(normalized.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return FREE;
    }
  }

  private static PlanTier aliasOrNull(String normalized) {
    return switch (normalized) {
      case "regular" -> FREE;
      case "silver" -> STARTER;
      case "gold" -> PRO;
      case "platinum" -> BUSINESS;
      default -> null;
    };
  }
}
