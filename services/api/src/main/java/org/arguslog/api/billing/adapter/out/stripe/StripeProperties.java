package org.arguslog.api.billing.adapter.out.stripe;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe wiring config. {@code apiKey} blank → checkout / portal / webhook endpoints return 503
 * with an explanatory message; we deliberately do not fail boot so dev environments without Stripe
 * can still run the rest of the api.
 *
 * <p>Webhook secret has its own env so the same key can serve test + prod with different webhook
 * subscriptions. Price IDs are env-driven so the same code can target Stripe test mode locally and
 * live mode in prod without a code change.
 */
@ConfigurationProperties(prefix = "arguslog.stripe")
public record StripeProperties(
    String apiKey,
    String webhookSecret,
    String priceProId,
    String priceProAnnualId,
    String dashboardBaseUrl) {

  public StripeProperties {
    if (apiKey == null) apiKey = "";
    if (webhookSecret == null) webhookSecret = "";
    if (priceProId == null) priceProId = "";
    if (priceProAnnualId == null) priceProAnnualId = "";
    if (dashboardBaseUrl == null || dashboardBaseUrl.isBlank()) {
      dashboardBaseUrl = "http://localhost:5173";
    }
  }

  public boolean configured() {
    return !apiKey.isBlank() && !priceProId.isBlank();
  }

  /** Annual billing requires its own Price ID; opt-in per deployment. */
  public boolean annualConfigured() {
    return configured() && !priceProAnnualId.isBlank();
  }

  /** Where Stripe redirects after a successful checkout — back to the org's billing page. */
  public String successUrl(long orgId) {
    return dashboardBaseUrl + "/orgs/" + orgId + "/billing?checkout=success";
  }

  /** Where Stripe redirects when the user clicks "back" in checkout. */
  public String cancelUrl(long orgId) {
    return dashboardBaseUrl + "/orgs/" + orgId + "/billing?checkout=cancelled";
  }
}
