package org.arguslog.crypto;

/**
 * Symmetric secret cipher used to wrap destination configs before they hit Postgres. AES-256-GCM
 * (authenticated) so a tampered ciphertext fails to decrypt. Implementation prefixes the ciphertext
 * with a 1-byte key version so a master-key rotation is just a config change + a background
 * re-encrypt of rows whose first byte is the old version.
 *
 * <p>Shared between api (encrypt + decrypt) and worker (decrypt only) so the byte layout cannot
 * drift between services. Each service registers the bean via its own @Configuration around
 * {@link AesGcmSecretCipher}.
 */
public interface SecretCipher {

  /** Encrypts {@code plaintext} with the current key version. Returns the wire bytes (versioned). */
  byte[] encrypt(byte[] plaintext);

  /** Decrypts {@code ciphertext}. Throws if MAC fails or the key version is unknown. */
  byte[] decrypt(byte[] ciphertext);
}
