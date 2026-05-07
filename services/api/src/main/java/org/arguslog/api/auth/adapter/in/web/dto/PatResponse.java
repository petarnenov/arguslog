package org.arguslog.api.auth.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.auth.domain.PersonalAccessToken;

/**
 * Read-side projection. {@code token} is populated only on the create response — list / get
 * responses omit it because the server cannot recover the plaintext from the argon2 hash.
 *
 * <p>{@code scopes} is the wire-form list ({@code "releases:write"}, …) of scopes the token
 * carries. {@code null} on the wire means "all scopes" (implicit-all, used by tokens minted before
 * V12).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PatResponse(
    long id,
    String name,
    String prefix,
    String token,
    @JsonProperty("expiresAt") Instant expiresAt,
    @JsonProperty("lastUsedAt") Instant lastUsedAt,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("scopes") List<String> scopes) {

  public static PatResponse from(PersonalAccessToken t) {
    return new PatResponse(
        t.id(),
        t.name(),
        t.prefix(),
        null,
        t.expiresAt(),
        t.lastUsedAt(),
        t.createdAt(),
        scopesWire(t));
  }

  public static PatResponse fromIssued(PersonalAccessToken t, String plaintext) {
    return new PatResponse(
        t.id(),
        t.name(),
        t.prefix(),
        plaintext,
        t.expiresAt(),
        t.lastUsedAt(),
        t.createdAt(),
        scopesWire(t));
  }

  private static List<String> scopesWire(PersonalAccessToken t) {
    return t.scopes() == null ? null : t.scopes().stream().map(PatScope::wire).sorted().toList();
  }
}
