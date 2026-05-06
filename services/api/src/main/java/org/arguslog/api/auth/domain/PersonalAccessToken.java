package org.arguslog.api.auth.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-side view of a {@code personal_access_tokens} row. The token plaintext is intentionally
 * absent — once minted it lives only on the user's clipboard.
 */
public record PersonalAccessToken(
    long id,
    UUID userId,
    String name,
    String prefix,
    Instant expiresAt,
    Instant lastUsedAt,
    Instant createdAt) {}
