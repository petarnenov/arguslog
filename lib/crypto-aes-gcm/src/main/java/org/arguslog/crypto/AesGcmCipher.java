package org.arguslog.crypto;

import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with a versioned wire format. Shared between api (encrypt + decrypt) and worker
 * (decrypt only) so the byte layout cannot drift between services.
 *
 * <p>Wire format:
 *
 * <pre>
 *   [0]      key version (currently {@link #CURRENT_VERSION})
 *   [1..12]  IV (12 bytes, fresh per encrypt)
 *   [13..]   ciphertext + 128-bit GCM auth tag
 * </pre>
 *
 * <p>The leading version byte makes master-key rotation a config bump + a background re-encrypt of
 * rows whose first byte is the old version — no schema migration. Real KMS integration (Cloudflare
 * Workers Secrets, AWS KMS, etc.) is a separate adapter; this class is the local fast-path used by
 * both services in production today.
 *
 * <p>Stateless after construction: instances are thread-safe (the underlying {@link Cipher} is
 * created fresh per call). One {@link SecureRandom} per instance for IV generation.
 */
public final class AesGcmCipher {

  public static final byte CURRENT_VERSION = 1;
  public static final int IV_LEN = 12;
  public static final int TAG_BITS = 128;
  private static final String TRANSFORM = "AES/GCM/NoPadding";

  /** Minimum encrypted payload size — version byte + IV + GCM auth tag. */
  public static final int MIN_CIPHERTEXT_LEN = 1 + IV_LEN + (TAG_BITS / 8);

  private final SecretKeySpec key;
  private final SecureRandom rng = new SecureRandom();

  /**
   * @param key32Bytes raw 32-byte AES-256 key. Use {@link #fromBase64(String)} when you have a
   *     base64-encoded master key from configuration.
   * @throws IllegalArgumentException if {@code key32Bytes.length != 32}.
   */
  public AesGcmCipher(byte[] key32Bytes) {
    if (key32Bytes == null || key32Bytes.length != 32) {
      throw new IllegalArgumentException(
          "AES-256 requires a 32-byte key; got "
              + (key32Bytes == null ? "null" : key32Bytes.length));
    }
    this.key = new SecretKeySpec(key32Bytes, "AES");
  }

  /** Decodes the supplied base64 string and constructs the cipher. */
  public static AesGcmCipher fromBase64(String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      throw new IllegalArgumentException("base64 key must be non-empty");
    }
    return new AesGcmCipher(java.util.Base64.getDecoder().decode(base64Key));
  }

  /** Encrypts {@code plaintext} and returns the wire bytes (versioned). */
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

  /**
   * Decrypts {@code ciphertext}.
   *
   * @throws IllegalArgumentException for malformed input or unknown key versions.
   * @throws IllegalStateException if the GCM tag fails to verify (tampered or wrong key).
   */
  public byte[] decrypt(byte[] ciphertext) {
    if (ciphertext == null || ciphertext.length < MIN_CIPHERTEXT_LEN) {
      throw new IllegalArgumentException("ciphertext too short");
    }
    byte version = ciphertext[0];
    if (version != CURRENT_VERSION) {
      throw new IllegalArgumentException(
          "unknown key version " + version + "; rotate the master key");
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
