package org.arguslog.api.billing.adapter.in.web.dto;

/** Response to {@code POST /billing/checkout-session}. {@code url} is hosted by Stripe. */
public record CheckoutResponse(String url) {}
