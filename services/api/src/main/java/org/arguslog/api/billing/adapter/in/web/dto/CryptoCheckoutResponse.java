package org.arguslog.api.billing.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CryptoCheckoutResponse(
    @JsonProperty("checkoutUrl") String checkoutUrl,
    @JsonProperty("invoiceReference") String invoiceReference) {}
