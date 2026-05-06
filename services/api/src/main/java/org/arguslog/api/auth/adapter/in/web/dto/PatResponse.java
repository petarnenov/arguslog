package org.arguslog.api.auth.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import org.arguslog.api.auth.domain.PersonalAccessToken;

/**
 * Read-side projection. {@code token} is populated only on the create response — list / get
 * responses omit it because the server cannot recover the plaintext from the argon2 hash.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PatResponse(
    long id,
    String name,
    String prefix,
    String token,
    @JsonProperty("expiresAt") Instant expiresAt,
    @JsonProperty("lastUsedAt") Instant lastUsedAt,
    @JsonProperty("createdAt") Instant createdAt) {

  public static PatResponse from(PersonalAccessToken t) {
    return new PatResponse(
        t.id(), t.name(), t.prefix(), null, t.expiresAt(), t.lastUsedAt(), t.createdAt());
  }

  public static PatResponse fromIssued(PersonalAccessToken t, String plaintext) {
    return new PatResponse(
        t.id(), t.name(), t.prefix(), plaintext, t.expiresAt(), t.lastUsedAt(), t.createdAt());
  }
}
