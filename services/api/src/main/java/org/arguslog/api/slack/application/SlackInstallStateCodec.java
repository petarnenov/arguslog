package org.arguslog.api.slack.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Encodes / decodes the {@code state} parameter that round-trips through Slack's authorize URL.
 *
 * <p>Wire format: {@code base64url(payloadJson) + "." + base64url(hmac-sha256(payloadJson))}.
 * Payload is a tiny JSON object — {@code orgId}, {@code userId}, a per-install {@code nonce},
 * and an absolute {@code exp} (epoch seconds). The HMAC key is {@code arguslog.slack.oauth
 * .state-secret} — kept distinct from the slash-command signing secret so leaking one doesn't
 * let an attacker forge the other.
 *
 * <p>Why not a real JWT library: state is short-lived (≤5min), single-issuer, single-audience —
 * a bespoke HMAC token is simpler, has no JOSE dependency, and matches what {@link
 * SlackSigningVerifier} already does for inbound Slack traffic.
 *
 * <p>Returns a typed {@link Result} variant instead of throwing because the callback path
 * routes all four failure modes (bad format / bad signature / expired / unparseable) to the
 * same 401 with a sanitized log line.
 */
@Component
@ConditionalOnProperty(name = "arguslog.slack.enabled", havingValue = "true", matchIfMissing = true)
public class SlackInstallStateCodec {

  static final Duration TTL = Duration.ofMinutes(5);

  private final byte[] key;
  private final Clock clock;
  private final ObjectMapper mapper;
  private final SecureRandom random;

  @Autowired
  public SlackInstallStateCodec(SlackOAuthProperties props, ObjectMapper mapper) {
    this(props.stateSecret(), Clock.systemUTC(), mapper, new SecureRandom());
  }

  /** Test ctor — pins clock + random so encode/decode round-trips are deterministic. */
  SlackInstallStateCodec(String stateSecret, Clock clock, ObjectMapper mapper, SecureRandom random) {
    this.key =
        stateSecret == null ? new byte[0] : stateSecret.getBytes(StandardCharsets.UTF_8);
    this.clock = clock;
    this.mapper = mapper;
    this.random = random;
  }

  /** Builds a fresh signed state token bound to the (orgId, userId) pair. */
  public String encode(long orgId, UUID userId) {
    if (key.length == 0) {
      throw new IllegalStateException(
          "SlackInstallStateCodec encode called without a configured state secret");
    }
    byte[] nonceBytes = new byte[16];
    random.nextBytes(nonceBytes);
    String nonce = base64UrlEncode(nonceBytes);
    Instant exp = clock.instant().plus(TTL);
    ObjectNode payload = mapper.createObjectNode();
    payload.put("orgId", orgId);
    payload.put("userId", userId.toString());
    payload.put("nonce", nonce);
    payload.put("exp", exp.getEpochSecond());
    byte[] payloadBytes;
    try {
      payloadBytes = mapper.writeValueAsBytes(payload);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("could not serialize state payload", e);
    }
    String encodedPayload = base64UrlEncode(payloadBytes);
    String mac = base64UrlEncode(hmac(payloadBytes));
    return encodedPayload + "." + mac;
  }

  /** Verifies signature + expiry. {@link Result} variants signal the four failure modes. */
  public Result decode(String token) {
    if (key.length == 0) return Result.invalid("state secret not configured");
    if (token == null || token.isBlank()) return Result.invalid("empty token");
    int dot = token.indexOf('.');
    if (dot < 0 || dot == token.length() - 1) return Result.invalid("bad format");
    byte[] payloadBytes;
    byte[] providedMac;
    try {
      payloadBytes = Base64.getUrlDecoder().decode(token.substring(0, dot));
      providedMac = Base64.getUrlDecoder().decode(token.substring(dot + 1));
    } catch (IllegalArgumentException e) {
      return Result.invalid("bad base64");
    }
    byte[] expectedMac = hmac(payloadBytes);
    if (!MessageDigest.isEqual(expectedMac, providedMac)) return Result.invalid("bad signature");

    long orgId;
    UUID userId;
    long exp;
    try {
      var node = mapper.readTree(payloadBytes);
      orgId = node.get("orgId").asLong();
      userId = UUID.fromString(node.get("userId").asText());
      exp = node.get("exp").asLong();
    } catch (Exception e) {
      return Result.invalid("malformed payload");
    }
    if (Instant.ofEpochSecond(exp).isBefore(clock.instant())) {
      return Result.invalid("expired");
    }
    return Result.ok(orgId, userId);
  }

  private byte[] hmac(byte[] message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(message);
    } catch (java.security.GeneralSecurityException e) {
      // Same fail-closed posture as SlackSigningVerifier — no JCE = always invalid.
      return new byte[0];
    }
  }

  private static String base64UrlEncode(byte[] in) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(in);
  }

  /** Decoded-state result. Use the static factories — the (orgId, userId) variant is "ok". */
  public sealed interface Result {
    static Result ok(long orgId, UUID userId) {
      return new Ok(orgId, userId);
    }

    static Result invalid(String reason) {
      return new Invalid(reason);
    }

    record Ok(long orgId, UUID userId) implements Result {}

    record Invalid(String reason) implements Result {}
  }
}
