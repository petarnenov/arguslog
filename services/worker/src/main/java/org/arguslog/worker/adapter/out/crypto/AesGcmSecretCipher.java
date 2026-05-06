package org.arguslog.worker.adapter.out.crypto;

import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.arguslog.worker.application.port.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decrypt-only twin of api's {@code AesGcmSecretCipher}. Wire format is locked:
 *
 * <pre>
 *   [0]      key version (currently 1)
 *   [1..12]  IV (12 bytes)
 *   [13..]   ciphertext + auth tag
 * </pre>
 *
 * <p>
 * Same env var name ({@code arguslog.alerts.secret-key}) so api and worker
 * share one key. TODO(P4):
 * extract this and the api copy to a shared module — the wire format is the
 * contract today, which
 * is fragile.
 */
@Component
public class AesGcmSecretCipher implements SecretCipher {

  private static final byte CURRENT_VERSION = 1;
  private static final int IV_LEN = 12;
  private static final int TAG_BITS = 128;
  private static final String TRANSFORM = "AES/GCM/NoPadding";

  private final SecretKeySpec key;

  public AesGcmSecretCipher(@Value("${arguslog.alerts.secret-key:}") String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      // Same dev fallback as api so both sides can decrypt each other's writes in
      // dev.
      byte[] devKey = "arguslog-dev-fallback-key-32byte".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      if (devKey.length != 32) {
        throw new IllegalStateException("dev fallback key must be 32 bytes; got " + devKey.length);
      }
      this.key = new SecretKeySpec(devKey, "AES");
      org.slf4j.LoggerFactory.getLogger(AesGcmSecretCipher.class)
          .warn(
              "arguslog.alerts.secret-key is empty — using the built-in dev key. DO NOT run prod like this.");
      return;
    }
    byte[] decoded = Base64.getDecoder().decode(base64Key);
    if (decoded.length != 32) {
      throw new IllegalArgumentException(
          "arguslog.alerts.secret-key must decode to 32 bytes (AES-256); got " + decoded.length);
    }
    this.key = new SecretKeySpec(decoded, "AES");
  }

  @Override
  public byte[] decrypt(byte[] ciphertext) {
    if (ciphertext == null || ciphertext.length < 1 + IV_LEN + 16) {
      throw new IllegalArgumentException("ciphertext too short");
    }
    byte version = ciphertext[0];
    if (version != CURRENT_VERSION) {
      throw new IllegalArgumentException(
          "unknown key version " + version + "; rotate arguslog.alerts.secret-key");
    }
    try {
      byte[] iv = new byte[IV_LEN];
      System.arraycopy(ciphertext, 1, iv, 0, IV_LEN);
      Cipher c = Cipher.getInstance(TRANSFORM);
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      return c.doFinal(ciphertext, 1 + IV_LEN, ciphertext.length - (1 + IV_LEN));
    } catch (Exception e) {
      throw new IllegalStateException("decrypt failed (tampered ciphertext or wrong key?)", e);
    }
  }
}
