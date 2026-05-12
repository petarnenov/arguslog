package org.arguslog.api.billing.adapter.out.nowpayments;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * NOWPayments REST + IPN wiring. Mirrors {@code StripeProperties} — blank values short-circuit to
 * 503 from the checkout endpoint instead of failing boot, so a dev or pre-launch environment
 * without NOWPayments credentials still runs the rest of the api.
 *
 * <p>{@code apiKey} signs server-to-server REST calls (header {@code x-api-key}). {@code ipnSecret}
 * is the HMAC-SHA512 key NOWPayments uses to sign every IPN webhook payload — see {@link
 * NowPaymentsIpnVerifier}. {@code dashboardBaseUrl} is shared with Stripe's redirects; kept as its
 * own property so the two providers can target different domains during a future staging-vs-prod
 * split.
 */
@ConfigurationProperties(prefix = "arguslog.nowpayments")
public record NowPaymentsProperties(
    String apiKey,
    String ipnSecret,
    String publicKey,
    String apiBaseUrl,
    String dashboardBaseUrl,
    String ipnCallbackUrl) {

  public NowPaymentsProperties {
    if (apiKey == null) apiKey = "";
    if (ipnSecret == null) ipnSecret = "";
    if (publicKey == null) publicKey = "";
    if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
      apiBaseUrl = "https://api.nowpayments.io/v1";
    }
    if (dashboardBaseUrl == null || dashboardBaseUrl.isBlank()) {
      dashboardBaseUrl = "http://localhost:5173";
    }
    if (ipnCallbackUrl == null) ipnCallbackUrl = "";
  }

  public boolean configured() {
    return !apiKey.isBlank() && !ipnSecret.isBlank();
  }

  public String successUrl(String orgSlug) {
    return dashboardBaseUrl + "/orgs/" + orgSlug + "/billing?checkout=success";
  }

  public String cancelUrl(String orgSlug) {
    return dashboardBaseUrl + "/orgs/" + orgSlug + "/billing?checkout=cancelled";
  }
}
