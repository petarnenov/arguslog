package org.arguslog.api.auth.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.auth.domain.PersonalAccessToken;

/** Persistence port for {@code personal_access_tokens}. */
public interface PatRepository {

  /**
   * Persists a new token. {@code scopes} of {@code null} maps to a NULL DB column (implicit-all);
   * an explicit empty set is treated the same. Otherwise the wire strings of each {@link PatScope}
   * get stored in the {@code scopes} TEXT[] column.
   */
  PersonalAccessToken create(
      UUID userId,
      String name,
      String prefix,
      String tokenHash,
      Instant expiresAt,
      Set<PatScope> scopes);

  List<PersonalAccessToken> listForUser(UUID userId);

  /** Look up by the prefix segment of the wire token. Returns the row + the stored argon2 hash. */
  Optional<PatRow> findByPrefix(String prefix);

  /** Bumps {@code last_used_at} to NOW for telemetry / "never used" indicators in the UI. */
  void recordUsage(long id, Instant when);

  /** Deletes the row scoped to the user; returns true if a row was removed. */
  boolean deleteForUser(UUID userId, long id);

  /** Bundle of token metadata + the stored hash, used only by the verify path. */
  record PatRow(PersonalAccessToken token, String tokenHash) {}
}
