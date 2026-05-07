package org.arguslog.api.billing.domain;

import java.util.Locale;

/**
 * Cadence the org pays at — monthly subscription or annual prepay (~17% off). Stripe holds the
 * canonical {@code Price} object; this enum is the wire contract for our api + DB column ({@code
 * organizations.billing_interval}).
 *
 * <p>Annual is opt-in: if {@code arguslog.stripe.price-pro-annual-id} is unset, the api refuses to
 * mint an annual checkout session and the dashboard hides the toggle. That keeps the deployment
 * matrix to "monthly always works, annual works when configured" without forcing both prices on
 * everyone.
 */
public enum BillingInterval {
  MONTHLY,
  ANNUAL;

  /** Wire string used in DB enum + the api. */
  public String dbValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Parses the wire/DB string. Unknown values fall back to {@link #MONTHLY} — same defensive
   * default as {@link PlanTier#fromDbValue}: a stray row never crashes a controller, the worst case
   * is the safer cadence.
   */
  public static BillingInterval fromDbValue(String raw) {
    if (raw == null) return MONTHLY;
    try {
      return BillingInterval.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return MONTHLY;
    }
  }
}
