package org.arguslog.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AesGcmCipherTest {

  private static final byte[] KEY =
      "0123456789ABCDEF0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);

  @Test
  void roundTrips() {
    AesGcmCipher cipher = new AesGcmCipher(KEY);
    byte[] plaintext = "secret webhook url".getBytes(StandardCharsets.UTF_8);

    byte[] ct = cipher.encrypt(plaintext);
    byte[] back = cipher.decrypt(ct);

    assertThat(back).isEqualTo(plaintext);
  }

  @Test
  void wireFormatIsVersionByteThenIvThenCiphertext() {
    AesGcmCipher cipher = new AesGcmCipher(KEY);
    byte[] ct = cipher.encrypt("x".getBytes(StandardCharsets.UTF_8));
    assertThat(ct[0]).isEqualTo(AesGcmCipher.CURRENT_VERSION);
    assertThat(ct.length).isGreaterThanOrEqualTo(AesGcmCipher.MIN_CIPHERTEXT_LEN);
  }

  @Test
  void differentInstancesProduceDifferentCiphertextsForSamePlaintext() {
    // IV is random per encrypt, so even a single instance encrypting the same input twice
    // returns distinct ciphertexts. Cross-instance shares the same property.
    AesGcmCipher a = new AesGcmCipher(KEY);
    AesGcmCipher b = new AesGcmCipher(KEY);
    byte[] plaintext = "same input".getBytes(StandardCharsets.UTF_8);
    assertThat(a.encrypt(plaintext)).isNotEqualTo(b.encrypt(plaintext));
  }

  @Test
  void decryptDetectsTamperedCiphertext() {
    AesGcmCipher cipher = new AesGcmCipher(KEY);
    byte[] ct = cipher.encrypt("payload".getBytes(StandardCharsets.UTF_8));
    ct[ct.length - 1] ^= 0x01; // flip a bit in the auth tag

    assertThatThrownBy(() -> cipher.decrypt(ct))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("decrypt failed");
  }

  @Test
  void decryptRejectsUnknownVersion() {
    AesGcmCipher cipher = new AesGcmCipher(KEY);
    byte[] ct = cipher.encrypt("payload".getBytes(StandardCharsets.UTF_8));
    ct[0] = 99;

    assertThatThrownBy(() -> cipher.decrypt(ct))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown key version");
  }

  @Test
  void decryptRejectsShortCiphertext() {
    AesGcmCipher cipher = new AesGcmCipher(KEY);
    assertThatThrownBy(() -> cipher.decrypt(new byte[5]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too short");
  }

  @Test
  void wrongKeySizeIsRejectedAtConstruction() {
    assertThatThrownBy(() -> new AesGcmCipher(new byte[16]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32-byte key");
  }

  @Test
  void fromBase64DecodesAndConstructs() {
    String b64 = java.util.Base64.getEncoder().encodeToString(KEY);
    AesGcmCipher cipher = AesGcmCipher.fromBase64(b64);
    byte[] ct = cipher.encrypt("hi".getBytes(StandardCharsets.UTF_8));
    assertThat(cipher.decrypt(ct)).isEqualTo("hi".getBytes(StandardCharsets.UTF_8));
  }
}
