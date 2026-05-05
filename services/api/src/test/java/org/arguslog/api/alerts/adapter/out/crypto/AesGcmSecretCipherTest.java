package org.arguslog.api.alerts.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AesGcmSecretCipherTest {

  private static final String TEST_KEY =
      Base64.getEncoder().encodeToString("the-key-must-be-32-bytes-long!!!".getBytes());

  @Test
  void roundTripsArbitraryBytes() {
    AesGcmSecretCipher cipher = new AesGcmSecretCipher(TEST_KEY);
    byte[] payload =
        "{\"chatId\":\"-100\",\"botToken\":\"123:abc\"}".getBytes(StandardCharsets.UTF_8);

    byte[] encrypted = cipher.encrypt(payload);

    assertThat(encrypted).isNotEqualTo(payload);
    assertThat(encrypted.length).isGreaterThan(payload.length); // version + iv + tag
    assertThat(cipher.decrypt(encrypted)).isEqualTo(payload);
  }

  @Test
  void freshIvPerEncrypt() {
    AesGcmSecretCipher cipher = new AesGcmSecretCipher(TEST_KEY);
    byte[] a = cipher.encrypt("same".getBytes(StandardCharsets.UTF_8));
    byte[] b = cipher.encrypt("same".getBytes(StandardCharsets.UTF_8));
    assertThat(a).isNotEqualTo(b); // different IV → different ciphertext for the same plaintext
  }

  @Test
  void tamperedCiphertextIsRefused() {
    AesGcmSecretCipher cipher = new AesGcmSecretCipher(TEST_KEY);
    byte[] encrypted = cipher.encrypt("hi".getBytes(StandardCharsets.UTF_8));
    encrypted[encrypted.length - 1] ^= 1; // flip the last bit of the auth tag
    assertThatThrownBy(() -> cipher.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void unknownKeyVersionIsRefusedExplicitly() {
    AesGcmSecretCipher cipher = new AesGcmSecretCipher(TEST_KEY);
    byte[] encrypted = cipher.encrypt("hi".getBytes(StandardCharsets.UTF_8));
    encrypted[0] = 99;
    assertThatThrownBy(() -> cipher.decrypt(encrypted))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown key version");
  }

  @Test
  void wrongLengthKeyIsRejectedAtConstruction() {
    String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());
    assertThatThrownBy(() -> new AesGcmSecretCipher(shortKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 bytes");
  }

  @Test
  void emptyKeyFallsBackToDevModeKey() {
    // Doesn't throw; ciphertext still round-trips. Loud warning is logged separately.
    AesGcmSecretCipher cipher = new AesGcmSecretCipher("");
    byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
    assertThat(cipher.decrypt(cipher.encrypt(payload))).isEqualTo(payload);
  }

  @Test
  void shortCiphertextIsRejected() {
    AesGcmSecretCipher cipher = new AesGcmSecretCipher(TEST_KEY);
    assertThatThrownBy(() -> cipher.decrypt(new byte[5]))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
