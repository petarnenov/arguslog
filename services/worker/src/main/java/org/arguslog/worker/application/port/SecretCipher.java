package org.arguslog.worker.application.port;

/**
 * Symmetric cipher used to unwrap destination configs read from Postgres. Worker only ever
 * decrypts; encryption stays in the api. The implementation must stay wire-compatible with api's
 * {@code AesGcmSecretCipher} (versioned byte prefix). TODO(P4): extract to a shared crypto module
 * so api and worker can't drift.
 */
public interface SecretCipher {

  byte[] decrypt(byte[] ciphertext);
}
