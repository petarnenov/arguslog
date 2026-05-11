package org.arguslog.crypto;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SecretCipher} impl wrapping the shared {@link AesGcmCipher}. Plain class (no framework
 * annotations) so this lib stays Spring-free — each service has its own @Configuration that
 * constructs the bean from its env-loaded master key.
 *
 * <p>Reads a base64-encoded 32-byte AES-256 master key. An empty value falls back to a built-in
 * dev key with a loud WARN at boot — DO NOT run prod that way.
 */
public final class AesGcmSecretCipher implements SecretCipher {

  private static final Logger log = LoggerFactory.getLogger(AesGcmSecretCipher.class);

  private static final byte[] DEV_FALLBACK_KEY =
      "arguslog-dev-fallback-key-32byte".getBytes(StandardCharsets.UTF_8);

  private final AesGcmCipher delegate;

  /**
   * @param base64Key 32-byte AES-256 master key, base64-encoded. Blank → dev fallback (warn).
   */
  public AesGcmSecretCipher(String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      log.warn(
          "AesGcmSecretCipher: base64 master key is empty — using the built-in dev key. DO NOT run prod like this.");
      this.delegate = new AesGcmCipher(DEV_FALLBACK_KEY);
      return;
    }
    this.delegate = AesGcmCipher.fromBase64(base64Key);
  }

  @Override
  public byte[] encrypt(byte[] plaintext) {
    return delegate.encrypt(plaintext);
  }

  @Override
  public byte[] decrypt(byte[] ciphertext) {
    return delegate.decrypt(ciphertext);
  }
}
