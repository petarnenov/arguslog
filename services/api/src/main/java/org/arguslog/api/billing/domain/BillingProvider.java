package org.arguslog.api.billing.domain;

import java.util.Locale;

/**
 * Which payment provider applied a plan purchase. Stored in {@code plan_purchases.provider} and
 * matches the {@code billing_provider_t} Postgres enum.
 *
 * <ul>
 *   <li>{@link #STRIPE} — legacy. Recurring subscriptions. Compiled in but only handles webhooks
 *       when {@code BILLING_PROVIDER=stripe} feature flag is set.
 *   <li>{@link #NOWPAYMENTS} — crypto stablecoin checkout. One-time prepaid.
 *   <li>{@link #LEMON_SQUEEZY} — Merchant of Record for cards/PayPal. One-time prepaid. Future.
 * </ul>
 */
public enum BillingProvider {
  STRIPE,
  NOWPAYMENTS,
  LEMON_SQUEEZY;

  public String dbValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static BillingProvider fromDbValue(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("billing provider must not be null");
    }
    return BillingProvider.valueOf(raw.toUpperCase(Locale.ROOT));
  }
}
