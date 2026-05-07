package org.arguslog.api.auth.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Wire format for {@code POST /api/v1/me/tokens}. {@code expiresAt} is optional. {@code scopes} is
 * the list of wire scope names ({@code "releases:write"}, {@code "alerts:read"}, …); when omitted
 * or empty the token is implicit-all (every scope) for backward compat.
 */
public record PatRequest(
    String name,
    @JsonProperty("expiresAt") Instant expiresAt,
    @JsonProperty("scopes") List<String> scopes) {}
