package org.arguslog.api.billing.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NowPaymentsWebhookResponse(@JsonProperty("outcome") String outcome) {}
