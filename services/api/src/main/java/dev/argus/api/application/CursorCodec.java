package dev.argus.api.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque {@code (instant, id)} pagination cursor — base64url("instant|id"). Two id flavors so the
 * issues endpoint (id is BIGINT) and the events endpoint (id is UUID) can share encoding without
 * leaking the difference to clients.
 *
 * <p>Callers MUST treat the encoded value as opaque. Format may change in any minor release.
 */
public final class CursorCodec {

  private CursorCodec() {}

  public static String encodeLong(Instant instant, long id) {
    return encode(instant, Long.toString(id));
  }

  public static String encodeUuid(Instant instant, UUID id) {
    return encode(instant, id.toString());
  }

  public static LongCursor decodeLong(String token) {
    Decoded d = decode(token);
    try {
      return new LongCursor(d.instant, Long.parseLong(d.id));
    } catch (NumberFormatException e) {
      throw new InvalidCursorException("cursor id is not a long", e);
    }
  }

  public static UuidCursor decodeUuid(String token) {
    Decoded d = decode(token);
    try {
      return new UuidCursor(d.instant, UUID.fromString(d.id));
    } catch (IllegalArgumentException e) {
      throw new InvalidCursorException("cursor id is not a UUID", e);
    }
  }

  public record LongCursor(Instant instant, long id) {}

  public record UuidCursor(Instant instant, UUID id) {}

  public static final class InvalidCursorException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidCursorException(String message) {
      super(message);
    }

    public InvalidCursorException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static String encode(Instant instant, String id) {
    String raw = instant.toString() + "|" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static Decoded decode(String token) {
    String raw;
    try {
      raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new InvalidCursorException("cursor is not valid base64url", e);
    }
    int sep = raw.indexOf('|');
    if (sep <= 0 || sep == raw.length() - 1) {
      throw new InvalidCursorException("cursor missing separator");
    }
    Instant ts;
    try {
      ts = Instant.parse(raw.substring(0, sep));
    } catch (RuntimeException e) {
      throw new InvalidCursorException("cursor instant unparseable", e);
    }
    return new Decoded(ts, raw.substring(sep + 1));
  }

  private record Decoded(Instant instant, String id) {}
}
