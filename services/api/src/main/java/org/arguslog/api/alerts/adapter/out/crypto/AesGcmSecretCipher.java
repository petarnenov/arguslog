package org.arguslog.api.alerts.adapter.out.crypto;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.arguslog.api.alerts.application.port.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM with a single env-vared master key. Wire format:
 *
 * <pre>
 *   [0]      key version (currently 1)
 *   [1..12]  IV (12 bytes, fresh per encrypt)
 *   [13..]   ciphertext + auth tag
 * </pre>
 *
 * <p>A version byte means rotation is just a config bump + a background re-encrypt of rows whose
 * first byte is the old version. Real KMS / Cloudflare Workers Secrets integration is the P4
 * billing-setup landing zone — the abstraction stays {@link SecretCipher}.
 */
@Component
public class AesGcmSecretCipher implements SecretCipher {

  private static final byte CURRENT_VERSION = 1;
  private static final int IV_LEN = 12;
  private static final int TAG_BITS = 128;
  private static final String TRANSFORM = "AES/GCM/NoPadding";

  private final SecretKeySpec key;
  private final SecureRandom rng = new SecureRandom();

  public AesGcmSecretCipher(@Value("${argus.alerts.secret-key:}") String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      // Dev-mode fallback: exactly 32 bytes — AES-256 won't accept anything else and we don't
      // want a hidden key-size mismatch in dev silently turning into AES-128. Loud warning so
      // this never makes it to prod.
      byte[] devKey =
          "argus-dev-fallback-key-32-bytes!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      if (devKey.length != 32) {
        throw new IllegalStateException("dev fallback key must be 32 bytes; got " + devKey.length);
      }
      this.key = new SecretKeySpec(devKey, "AES");
      org.slf4j.LoggerFactory.getLogger(AesGcmSecretCipher.class)
          .warn(
              "argus.alerts.secret-key is empty — using the built-in dev key. DO NOT run prod like this.");
      return;
    }
    byte[] decoded = Base64.getDecoder().decode(base64Key);
    if (decoded.length != 32) {
      throw new IllegalArgumentException(
          "argus.alerts.secret-key must decode to 32 bytes (AES-256); got " + decoded.length);
    }
    this.key = new SecretKeySpec(decoded, "AES");
  }

  @Override
  public byte[] encrypt(byte[] plaintext) {
    try {
      byte[] iv = new byte[IV_LEN];
      rng.nextBytes(iv);
      Cipher c = Cipher.getInstance(TRANSFORM);
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ct = c.doFinal(plaintext);
      byte[] out = new byte[1 + IV_LEN + ct.length];
      out[0] = CURRENT_VERSION;
      System.arraycopy(iv, 0, out, 1, IV_LEN);
      System.arraycopy(ct, 0, out, 1 + IV_LEN, ct.length);
      return out;
    } catch (Exception e) {
      throw new IllegalStateException("encrypt failed", e);
    }
  }

  @Override
  public byte[] decrypt(byte[] ciphertext) {
    if (ciphertext == null || ciphertext.length < 1 + IV_LEN + 16) {
      throw new IllegalArgumentException("ciphertext too short");
    }
    byte version = ciphertext[0];
    if (version != CURRENT_VERSION) {
      throw new IllegalArgumentException(
          "unknown key version " + version + "; rotate the master key in argus.alerts.secret-key");
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
