package org.arguslog.api.auth.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.auth.domain.PersonalAccessToken;

public interface PatUseCase {

  /**
   * Mints a new PAT for the user. Plaintext is in {@link Issued#plaintext} — only time the api ever
   * returns it; caller MUST surface to the user immediately and discard.
   */
  Issued create(UUID userId, String name, Instant expiresAt);

  List<PersonalAccessToken> list(UUID userId);

  /** Verifies a wire token and (on success) bumps {@code last_used_at}. */
  Optional<PersonalAccessToken> verify(String wireToken, Instant now);

  boolean revoke(UUID userId, long tokenId);

  record Issued(PersonalAccessToken token, String plaintext) {}

  /** Thrown when a PAT name is null/blank or longer than the limit. */
  final class InvalidPatException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidPatException(String message) {
      super(message);
    }
  }
}
