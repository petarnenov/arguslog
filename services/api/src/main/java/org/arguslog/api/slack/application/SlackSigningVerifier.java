package org.arguslog.api.slack.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies Slack signed-request payloads. Slack signs every slash-command POST with {@code
 * v0=hex(HMAC-SHA256(signing_secret, "v0:" + timestamp + ":" + raw_body))} and the timestamp itself
 * comes in via the {@code X-Slack-Request-Timestamp} header — see the official spec at
 * https://docs.slack.dev/authentication/verifying-requests-from-slack.
 *
 * <p>This class is intentionally a pure function over (timestamp, body, signature) — the HTTP
 * filter that wires it (Phase 3) reads the headers + body buffer; we don't take a dependency on
 * servlets here so the verifier can be unit-tested without the controller stack.
 *
 * <p>Replay protection: signatures older than {@link #REPLAY_WINDOW} are rejected even if the MAC
 * matches. Slack's own guidance is ±5 minutes; we pick exactly 5 minutes both directions to match.
 * A short tolerance for future-skew is included (≤30s) so a slightly fast Slack load-balancer clock
 * doesn't blacklist real traffic.
 */
@Component
public class SlackSigningVerifier {

  static final Duration REPLAY_WINDOW = Duration.ofMinutes(5);
  static final Duration FUTURE_SKEW = Duration.ofSeconds(30);

  private final byte[] signingSecret;
  private final Clock clock;

  @Autowired
  public SlackSigningVerifier(@Value("${SLACK_SIGNING_SECRET:}") String signingSecret) {
    this(signingSecret, Clock.systemUTC());
  }

  /** Test-only ctor that swaps in a deterministic clock so replay-window tests stay sane. */
  SlackSigningVerifier(String signingSecret, Clock clock) {
    this.signingSecret =
        signingSecret == null ? new byte[0] : signingSecret.getBytes(StandardCharsets.UTF_8);
    this.clock = clock;
  }

  /**
   * Returns {@code true} iff:
   *
   * <ol>
   *   <li>the signing secret is configured (empty secret ⇒ always reject — fail-closed in any
   *       environment where Slack isn't supposed to be wired);
   *   <li>the timestamp parses as epoch seconds and is within the replay window;
   *   <li>the recomputed HMAC matches the provided signature byte-for-byte via a constant-time
   *       compare.
   * </ol>
   *
   * Caller is expected to log {@code false} returns at info level — these are the only signal that
   * something is wrong on the boundary.
   */
  public boolean verify(String timestampHeader, String body, String signatureHeader) {
    if (signingSecret.length == 0) return false;
    if (timestampHeader == null || body == null || signatureHeader == null) return false;
    if (!signatureHeader.startsWith("v0=")) return false;

    long timestamp;
    try {
      timestamp = Long.parseLong(timestampHeader);
    } catch (NumberFormatException e) {
      return false;
    }

    Instant signed = Instant.ofEpochSecond(timestamp);
    Instant now = clock.instant();
    if (signed.isBefore(now.minus(REPLAY_WINDOW))) return false;
    if (signed.isAfter(now.plus(FUTURE_SKEW))) return false;

    String basestring = "v0:" + timestamp + ":" + body;
    String expected = "v0=" + hexHmacSha256(signingSecret, basestring);
    return constantTimeEquals(
        expected.getBytes(StandardCharsets.UTF_8),
        signatureHeader.getBytes(StandardCharsets.UTF_8));
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
    } catch (java.security.GeneralSecurityException e) {
      // No JCE provider for HmacSHA256 → catastrophic; treat as MAC mismatch so we still
      // fail-closed instead of throwing 500 to Slack.
      return "";
    }
  }

  private static boolean constantTimeEquals(byte[] a, byte[] b) {
    return MessageDigest.isEqual(a, b);
  }
}
