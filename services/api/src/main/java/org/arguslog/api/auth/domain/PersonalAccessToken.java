package org.arguslog.api.auth.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Read-side view of a {@code personal_access_tokens} row. The token plaintext is intentionally
 * absent — once minted it lives only on the user's clipboard.
 *
 * <p>{@code scopes} is the granular capability set. {@code null} means "all scopes" — the
 * implicit-all contract for tokens minted before the {@code scopes} column existed (V12). The auth
 * filter promotes each scope into a {@code SCOPE_*} {@link
 * org.springframework.security.core.GrantedAuthority}.
 */
public record PersonalAccessToken(
    long id,
    UUID userId,
    String name,
    String prefix,
    Instant expiresAt,
    Instant lastUsedAt,
    Instant createdAt,
    Set<PatScope> scopes) {

  /** Effective scope set — explicit set if the token has one, otherwise the all-scopes default. */
  public Set<PatScope> effectiveScopes() {
    return scopes == null || scopes.isEmpty() ? PatScope.all() : scopes;
  }
}
