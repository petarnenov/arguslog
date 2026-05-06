package org.arguslog.api.auth.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.auth.application.port.PatRepository;
import org.arguslog.api.auth.application.port.PatRepository.PatRow;
import org.arguslog.api.auth.application.port.TokenHasher;
import org.arguslog.api.auth.domain.PersonalAccessToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints and verifies PATs. Wire format:
 *
 * <pre>arglog_pat_&lt;PREFIX:8 base32-style chars&gt;_&lt;SECRET:48 chars&gt;</pre>
 *
 * The {@code prefix} is plaintext in the DB so the auth filter can fetch the row in O(1) before
 * paying the argon2 verify cost on the {@code secret} portion.
 */
@Service
public class PatService implements PatUseCase {

  static final String TOKEN_PREFIX = "arglog_pat_";
  static final int PREFIX_LENGTH = 8;
  static final int SECRET_LENGTH = 48;
  static final int MAX_NAME_LENGTH = 100;

  // base62-ish — alphanumeric only so the token round-trips cleanly through CLIs and HTTP clients
  // that may not URL-encode bearer headers.
  private static final char[] ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

  private final PatRepository repository;
  private final TokenHasher hasher;
  private final SecureRandom rng;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public PatService(PatRepository repository, TokenHasher hasher, Clock clock) {
    this(repository, hasher, clock, new SecureRandom());
  }

  // Test-only constructor — pin SecureRandom for deterministic prefix collisions.
  PatService(PatRepository repository, TokenHasher hasher, Clock clock, SecureRandom rng) {
    this.repository = repository;
    this.hasher = hasher;
    this.clock = clock;
    this.rng = rng;
  }

  @Override
  @Transactional
  public Issued create(UUID userId, String name, Instant expiresAt) {
    String trimmedName = requireName(name);
    String prefix = randomString(PREFIX_LENGTH);
    String secret = randomString(SECRET_LENGTH);
    String wireToken = TOKEN_PREFIX + prefix + "_" + secret;
    String hash = hasher.hash(wireToken);
    PersonalAccessToken stored = repository.create(userId, trimmedName, prefix, hash, expiresAt);
    return new Issued(stored, wireToken);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PersonalAccessToken> list(UUID userId) {
    return repository.listForUser(userId);
  }

  @Override
  @Transactional
  public Optional<PersonalAccessToken> verify(String wireToken, Instant now) {
    if (wireToken == null || !wireToken.startsWith(TOKEN_PREFIX)) return Optional.empty();
    String body = wireToken.substring(TOKEN_PREFIX.length());
    int sep = body.indexOf('_');
    if (sep != PREFIX_LENGTH) return Optional.empty();
    String prefix = body.substring(0, sep);

    Optional<PatRow> row = repository.findByPrefix(prefix);
    if (row.isEmpty()) return Optional.empty();
    PatRow r = row.get();
    if (r.token().expiresAt() != null && !r.token().expiresAt().isAfter(now)) {
      return Optional.empty();
    }
    if (!hasher.matches(wireToken, r.tokenHash())) return Optional.empty();
    repository.recordUsage(r.token().id(), Instant.now(clock));
    return Optional.of(r.token());
  }

  @Override
  @Transactional
  public boolean revoke(UUID userId, long tokenId) {
    return repository.deleteForUser(userId, tokenId);
  }

  private static String requireName(String raw) {
    if (raw == null) throw new InvalidPatException("name is required");
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) throw new InvalidPatException("name is required");
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new InvalidPatException("name must be " + MAX_NAME_LENGTH + " characters or fewer");
    }
    return trimmed;
  }

  private String randomString(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]);
    }
    return sb.toString();
  }
}
