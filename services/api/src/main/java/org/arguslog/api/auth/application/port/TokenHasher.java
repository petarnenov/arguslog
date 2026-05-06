package org.arguslog.api.auth.application.port;

/**
 * Hashes / verifies arbitrary opaque tokens. Implementation must be a slow, constant-time password
 * hash (argon2id today).
 */
public interface TokenHasher {

  String hash(String plaintext);

  boolean matches(String plaintext, String storedHash);
}
