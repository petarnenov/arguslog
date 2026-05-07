package org.arguslog.api.alerts.adapter.out.crypto;

import org.arguslog.api.alerts.application.port.SecretCipher;
import org.arguslog.crypto.AesGcmCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring adapter that wraps the shared {@link AesGcmCipher} (in {@code :lib:crypto-aes-gcm}) so api
 * and worker cannot drift on the at-rest wire format. The {@link SecretCipher} port stays
 * api-internal; only the byte layout is shared.
 *
 * <p>Reads {@code arguslog.alerts.secret-key} as a base64-encoded 32-byte AES-256 master key. An
 * empty value falls back to a built-in dev key with a loud WARN at boot — DO NOT run prod that way.
 */
@Component
public class AesGcmSecretCipher implements SecretCipher {

  private static final byte[] DEV_FALLBACK_KEY =
      "arguslog-dev-fallback-key-32byte".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  private final AesGcmCipher delegate;

  public AesGcmSecretCipher(@Value("${arguslog.alerts.secret-key:}") String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      org.slf4j.LoggerFactory.getLogger(AesGcmSecretCipher.class)
          .warn(
              "arguslog.alerts.secret-key is empty — using the built-in dev key. DO NOT run prod like this.");
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
