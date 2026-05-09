package org.arguslog.api.billing.domain;

import java.util.Locale;

/**
 * Cadence the org pays at. Two semantic groups share one DB column ({@code
 * organizations.billing_interval}):
 *
 * <ul>
 *   <li>Stripe legacy (recurring): {@link #MONTHLY}, {@link #ANNUAL} — set by Stripe webhooks
 *       when an active subscription is observed. Compiled in but only runs when
 *       {@code BILLING_PROVIDER=stripe}.
 *   <li>One-time prepaid (NOWPayments + Lemon Squeezy): {@link #ONE_MONTH}, {@link
 *       #THREE_MONTHS}, {@link #SIX_MONTHS}, {@link #TWELVE_MONTHS} — set by crypto/MoR webhooks
 *       on a successful purchase. The org's plan expires at {@code applied_at + duration} unless
 *       another purchase extends it.
 * </ul>
 */
public enum BillingInterval {
  MONTHLY(1, "monthly"),
  ANNUAL(12, "annual"),
  ONE_MONTH(1, "one_month"),
  THREE_MONTHS(3, "three_months"),
  SIX_MONTHS(6, "six_months"),
  TWELVE_MONTHS(12, "twelve_months");

  private final int months;
  private final String dbValue;

  BillingInterval(int months, String dbValue) {
    this.months = months;
    this.dbValue = dbValue;
  }

  public int months() {
    return months;
  }

  public String dbValue() {
    return dbValue;
  }

  public static BillingInterval fromDbValue(String raw) {
    if (raw == null) return ONE_MONTH;
    String normalized = raw.toLowerCase(Locale.ROOT);
    for (BillingInterval value : values()) {
      if (value.dbValue.equals(normalized)) return value;
    }
    return ONE_MONTH;
  }

  public static BillingInterval fromMonths(int months) {
    return switch (months) {
      case 1 -> ONE_MONTH;
      case 3 -> THREE_MONTHS;
      case 6 -> SIX_MONTHS;
      case 12 -> TWELVE_MONTHS;
      default -> throw new IllegalArgumentException(
          "Unsupported one-time duration: " + months + " months. Allowed: 1, 3, 6, 12.");
    };
  }
}
