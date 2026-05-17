package org.arguslog.api.slack.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Slack signing-secret verifier — the only thing standing between a forged HTTP POST and the triage
 * commands. Replay window + constant-time compare + fail-closed when secret is unset.
 */
class SlackSigningVerifierTest {

  private static final String SECRET = "8f742231b10e8888abcd99ff666";
  private static final Instant NOW = Instant.parse("2026-05-15T12:00:00Z");
  private static final Clock FROZEN = Clock.fixed(NOW, ZoneOffset.UTC);

  private final SlackSigningVerifier verifier = new SlackSigningVerifier(SECRET, FROZEN);

  @Test
  void freshlySignedRequestPasses() {
    long ts = NOW.getEpochSecond();
    String body = "token=xyz&team_id=T123&command=%2Farguslog&text=issues";
    String sig = sign(ts, body);

    assertThat(verifier.verify(String.valueOf(ts), body, sig)).isTrue();
  }

  @Test
  void tamperedBodyFails() {
    long ts = NOW.getEpochSecond();
    String body = "token=xyz&team_id=T123&command=%2Farguslog&text=issues";
    String sig = sign(ts, body);
    // Attacker swaps `issues` → `resolve+999` keeping the original signature.
    String tampered = "token=xyz&team_id=T123&command=%2Farguslog&text=resolve+999";
    assertThat(verifier.verify(String.valueOf(ts), tampered, sig)).isFalse();
  }

  @Test
  void wrongSecretFails() {
    long ts = NOW.getEpochSecond();
    String body = "token=xyz";
    String sig =
        "v0="
            + hexHmacSha256(
                "different-secret".getBytes(StandardCharsets.UTF_8), "v0:" + ts + ":" + body);

    assertThat(verifier.verify(String.valueOf(ts), body, sig)).isFalse();
  }

  @Test
  void replayOlderThanFiveMinutesFails() {
    long oldTs = NOW.minusSeconds(301).getEpochSecond();
    String body = "token=xyz";
    String sig = sign(oldTs, body);

    assertThat(verifier.verify(String.valueOf(oldTs), body, sig)).isFalse();
  }

  @Test
  void replayInsideFiveMinutesPasses() {
    long ts = NOW.minusSeconds(299).getEpochSecond();
    String body = "token=xyz";
    String sig = sign(ts, body);

    assertThat(verifier.verify(String.valueOf(ts), body, sig)).isTrue();
  }

  @Test
  void futureTimestampOutsideSkewFails() {
    long futureTs = NOW.plusSeconds(31).getEpochSecond();
    String body = "token=xyz";
    String sig = sign(futureTs, body);

    assertThat(verifier.verify(String.valueOf(futureTs), body, sig)).isFalse();
  }

  @Test
  void unsetSecretAlwaysFailsClosed() {
    SlackSigningVerifier unconfigured = new SlackSigningVerifier("", FROZEN);
    long ts = NOW.getEpochSecond();
    assertThat(unconfigured.verify(String.valueOf(ts), "anything", "v0=" + "0".repeat(64)))
        .isFalse();
  }

  @Test
  void nonV0PrefixFails() {
    long ts = NOW.getEpochSecond();
    String body = "token=xyz";
    String sig = sign(ts, body);
    String withoutPrefix = sig.substring(3); // strip "v0="
    assertThat(verifier.verify(String.valueOf(ts), body, withoutPrefix)).isFalse();
  }

  @Test
  void nonNumericTimestampFails() {
    String body = "token=xyz";
    String sig = sign(NOW.getEpochSecond(), body);
    assertThat(verifier.verify("not-a-number", body, sig)).isFalse();
  }

  @Test
  void nullInputsFailWithoutThrowing() {
    long ts = NOW.getEpochSecond();
    String sig = sign(ts, "body");
    assertThat(verifier.verify(null, "body", sig)).isFalse();
    assertThat(verifier.verify(String.valueOf(ts), null, sig)).isFalse();
    assertThat(verifier.verify(String.valueOf(ts), "body", null)).isFalse();
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static String sign(long timestamp, String body) {
    return "v0="
        + hexHmacSha256(SECRET.getBytes(StandardCharsets.UTF_8), "v0:" + timestamp + ":" + body);
  }

  private static String hexHmacSha256(byte[] key, String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(sig.length * 2);
      for (byte b : sig) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
