package org.arguslog.api.alerts.application.port;

/**
 * Symmetric secret cipher used to wrap destination configs before they hit Postgres. AES-256-GCM
 * (authenticated) so a tampered ciphertext fails to decrypt. Implementation prefixes the ciphertext
 * with a 1-byte key version so a master-key rotation is just a config change + a background
 * re-encrypt of rows whose first byte is the old version.
 */
public interface SecretCipher {

  /**
   * Encrypts {@code plaintext} with the current key version. Returns the wire bytes (versioned).
   */
  byte[] encrypt(byte[] plaintext);

  /** Decrypts {@code ciphertext}. Throws if MAC fails or the key version is unknown. */
  byte[] decrypt(byte[] ciphertext);
}
