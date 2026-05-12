package org.arguslog.api.billing.adapter.out.nowpayments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifies the {@code x-nowpayments-sig} HMAC-SHA512 signature NOWPayments stamps on every IPN.
 *
 * <p>The signing payload is the IPN body re-serialized with keys sorted alphabetically (recursively
 * — nested objects too). Jackson's {@code ORDER_MAP_ENTRIES_BY_KEYS} feature does this in one pass;
 * we then HMAC-SHA512 the resulting bytes with the IPN secret and hex-encode.
 *
 * <p>The compare uses constant-time {@link MessageDigest#isEqual} to keep the verification
 * timing-safe — important because the IPN endpoint is publicly reachable.
 */
@Component
public class NowPaymentsIpnVerifier {

  private static final ObjectMapper SORTED_MAPPER =
      new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  private final NowPaymentsProperties props;

  public NowPaymentsIpnVerifier(NowPaymentsProperties props) {
    this.props = props;
  }

  public boolean isValid(String rawJsonBody, String signatureHeader) {
    if (signatureHeader == null || signatureHeader.isBlank()) return false;
    if (!props.configured()) return false;
    try {
      Object parsed = SORTED_MAPPER.readValue(rawJsonBody, Object.class);
      String canonical = SORTED_MAPPER.writeValueAsString(parsed);
      String expected = hmacSha512Hex(canonical, props.ipnSecret());
      return java.security.MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.UTF_8),
          signatureHeader.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      return false;
    }
  }

  private static String hmacSha512Hex(String message, String secret) throws Exception {
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
