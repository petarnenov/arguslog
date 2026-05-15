package org.arguslog.api.slack.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Round-trips the state token codec — happy path, tampered signature, expiry, junk input. The
 * codec sits in the OAuth attack surface alongside {@link SlackSigningVerifier}, so the same
 * fail-closed posture (any decode failure → {@link SlackInstallStateCodec.Result.Invalid})
 * gets pinned down explicitly.
 */
class SlackInstallStateCodecTest {

  private static final String SECRET = "state-secret-not-the-signing-one";
  private static final Instant NOW = Instant.parse("2026-05-15T12:00:00Z");
  private static final Clock FROZEN = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UUID USER = UUID.fromString("c0ffee00-dead-beef-cafe-deadbeefcafe");
  private static final long ORG = 42;

  private final ObjectMapper mapper = new ObjectMapper();
  private final SlackInstallStateCodec codec =
      new SlackInstallStateCodec(SECRET, FROZEN, mapper, new SecureRandom());

  @Test
  void freshlyEncodedTokenDecodesBackToInputs() {
    String token = codec.encode(ORG, USER);

    SlackInstallStateCodec.Result result = codec.decode(token);

    assertThat(result).isInstanceOf(SlackInstallStateCodec.Result.Ok.class);
    var ok = (SlackInstallStateCodec.Result.Ok) result;
    assertThat(ok.orgId()).isEqualTo(ORG);
    assertThat(ok.userId()).isEqualTo(USER);
  }

  @Test
  void twoEncodesProduceDifferentTokens() {
    // The nonce inside the payload makes every encode unique even for the same (orgId, userId);
    // catches a regression where a deterministic encoder would let an attacker replay the same
    // state across two install attempts.
    String a = codec.encode(ORG, USER);
    String b = codec.encode(ORG, USER);
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void tamperedPayloadIsRejected() {
    String token = codec.encode(ORG, USER);
    int dot = token.indexOf('.');
    String payload = token.substring(0, dot);
    String mac = token.substring(dot + 1);
    // Flip a single character in the base64url payload — Slack's spec doesn't say what an
    // attacker substitutes; this is "mac mismatch" land.
    char first = payload.charAt(0);
    String tamperedFirst = (first == 'A' ? "B" : "A") + payload.substring(1);
    String tampered = tamperedFirst + "." + mac;

    assertThat(codec.decode(tampered)).isInstanceOf(SlackInstallStateCodec.Result.Invalid.class);
  }

  @Test
  void tamperedSignatureIsRejected() {
    String token = codec.encode(ORG, USER);
    int dot = token.indexOf('.');
    String payload = token.substring(0, dot);
    // Swap the MAC for one that's the right length but obviously wrong.
    String tampered = payload + "." + "AAAA".repeat(11);

    assertThat(codec.decode(tampered)).isInstanceOf(SlackInstallStateCodec.Result.Invalid.class);
  }

  @Test
  void expiredTokenIsRejected() {
    String token = codec.encode(ORG, USER);
    // Advance the clock past the 5-minute TTL.
    SlackInstallStateCodec future =
        new SlackInstallStateCodec(
            SECRET,
            Clock.fixed(NOW.plus(SlackInstallStateCodec.TTL).plusSeconds(1), ZoneOffset.UTC),
            mapper,
            new SecureRandom());
    assertThat(future.decode(token)).isInstanceOf(SlackInstallStateCodec.Result.Invalid.class);
  }

  @Test
  void freshlyExpiredTokenAtBoundaryStillFailsClosed() {
    // exp encodes seconds; clock equal to exp is "not before", so verifier rejects ≥1s past exp.
    String token = codec.encode(ORG, USER);
    SlackInstallStateCodec atBoundary =
        new SlackInstallStateCodec(
            SECRET,
            Clock.fixed(NOW.plus(SlackInstallStateCodec.TTL).plusSeconds(1), ZoneOffset.UTC),
            mapper,
            new SecureRandom());
    assertThat(atBoundary.decode(token)).isInstanceOf(SlackInstallStateCodec.Result.Invalid.class);
  }

  @Test
  void wrongSecretRejects() {
    String token = codec.encode(ORG, USER);
    SlackInstallStateCodec other =
        new SlackInstallStateCodec("different-secret", FROZEN, mapper, new SecureRandom());
    assertThat(other.decode(token)).isInstanceOf(SlackInstallStateCodec.Result.Invalid.class);
  }

  @Test
  void emptySecretFailsClosedOnDecode() {
    SlackInstallStateCodec unconfigured =
        new SlackInstallStateCodec("", FROZEN, mapper, new SecureRandom());
    assertThat(unconfigured.decode("anything.AAAA"))
        .isInstanceOf(SlackInstallStateCodec.Result.Invalid.class);
  }

  @Test
  void nullEmptyAndMalformedTokensAreInvalid() {
    assertThat(codec.decode(null)).isInstanceOf(SlackInstallStateCodec.Result.Invalid.class);
    assertThat(codec.decode("")).isInstanceOf(SlackInstallStateCodec.Result.Invalid.class);
    assertThat(codec.decode("no-dot-here"))
        .isInstanceOf(SlackInstallStateCodec.Result.Invalid.class);
    assertThat(codec.decode("trailing-dot."))
        .isInstanceOf(SlackInstallStateCodec.Result.Invalid.class);
    assertThat(codec.decode("not!base64.AAAA"))
        .isInstanceOf(SlackInstallStateCodec.Result.Invalid.class);
  }
}
