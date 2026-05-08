package org.arguslog.api.billing.adapter.in.web;

/**
 * Body of a successful Stripe webhook ack. Stripe itself ignores the body — it only reads the
 * status code — but the {@code outcome} is useful for ops dashboards to distinguish PROCESSED vs
 * ALREADY_SEEN vs IGNORED vs UNKNOWN_CUSTOMER.
 */
public record StripeWebhookResponse(String outcome) {}
