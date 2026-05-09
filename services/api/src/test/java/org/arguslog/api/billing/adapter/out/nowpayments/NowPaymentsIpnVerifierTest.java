package org.arguslog.api.billing.adapter.out.nowpayments;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class NowPaymentsIpnVerifierTest {

  private static final String SECRET = "test-secret-please-rotate";

  private final NowPaymentsIpnVerifier verifier =
      new NowPaymentsIpnVerifier(
          new NowPaymentsProperties(
              "test-api-key", SECRET, "", null, "https://app.test", "https://api.test/ipn"));

  @Test
  void acceptsSignatureOverAlphabeticallySortedJson() throws Exception {
    String body = "{\"order_id\":\"abc\",\"payment_status\":\"finished\",\"payment_id\":\"42\"}";
    String canonical =
        "{\"order_id\":\"abc\",\"payment_id\":\"42\",\"payment_status\":\"finished\"}";
    String sig = hmacHex(canonical, SECRET);

    assertThat(verifier.isValid(body, sig)).isTrue();
  }

  @Test
  void acceptsSignatureRegardlessOfInputKeyOrder() throws Exception {
    String body1 = "{\"a\":1,\"z\":\"last\",\"m\":\"middle\"}";
    String body2 = "{\"z\":\"last\",\"m\":\"middle\",\"a\":1}";
    String canonical = "{\"a\":1,\"m\":\"middle\",\"z\":\"last\"}";
    String sig = hmacHex(canonical, SECRET);

    assertThat(verifier.isValid(body1, sig)).isTrue();
    assertThat(verifier.isValid(body2, sig)).isTrue();
  }

  @Test
  void rejectsTamperedBody() throws Exception {
    String original = "{\"payment_id\":\"42\",\"payment_status\":\"finished\"}";
    String sig = hmacHex(original, SECRET);
    String tampered = "{\"payment_id\":\"42\",\"payment_status\":\"failed\"}";

    assertThat(verifier.isValid(tampered, sig)).isFalse();
  }

  @Test
  void rejectsWrongSignature() {
    String body = "{\"payment_id\":\"42\",\"payment_status\":\"finished\"}";
    String forged = "0".repeat(128);

    assertThat(verifier.isValid(body, forged)).isFalse();
  }

  @Test
  void rejectsMissingSignatureHeader() {
    String body = "{\"payment_id\":\"42\",\"payment_status\":\"finished\"}";
    assertThat(verifier.isValid(body, null)).isFalse();
    assertThat(verifier.isValid(body, "")).isFalse();
  }

  @Test
  void rejectsWhenSecretNotConfigured() throws Exception {
    NowPaymentsIpnVerifier unconfigured =
        new NowPaymentsIpnVerifier(
            new NowPaymentsProperties("test-api-key", "", "", null, "https://app.test", ""));
    String body = "{\"payment_id\":\"42\",\"payment_status\":\"finished\"}";
    String sig = hmacHex(body, "anything");

    assertThat(unconfigured.isValid(body, sig)).isFalse();
  }

  @Test
  void rejectsMalformedJsonBody() throws Exception {
    String malformed = "{not-json";
    String sig = hmacHex(malformed, SECRET);
    assertThat(verifier.isValid(malformed, sig)).isFalse();
  }

  private static String hmacHex(String message, String secret) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA512");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
    byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      hex.append(String.format("%02x", b & 0xff));
    }
    return hex.toString();
  }
}
