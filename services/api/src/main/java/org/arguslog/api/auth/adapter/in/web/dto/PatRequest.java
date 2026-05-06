package org.arguslog.api.auth.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** Wire format for {@code POST /api/v1/me/tokens}. {@code expiresAt} is optional. */
public record PatRequest(String name, @JsonProperty("expiresAt") Instant expiresAt) {}
