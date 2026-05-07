package org.arguslog.worker.adapter.out.crypto;

import org.arguslog.crypto.AesGcmCipher;
import org.arguslog.worker.application.port.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring adapter that wraps the shared {@link AesGcmCipher} (in {@code :lib:crypto-aes-gcm}) so api
 * and worker cannot drift on the at-rest wire format. Worker only ever decrypts; encryption stays
 * in api.
 *
 * <p>Same env var ({@code arguslog.alerts.secret-key}) as api so a single key serves both services.
 * Empty value falls back to the built-in dev key with a loud WARN at boot.
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
  public byte[] decrypt(byte[] ciphertext) {
    return delegate.decrypt(ciphertext);
  }
}
