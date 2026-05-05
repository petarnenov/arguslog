package org.arguslog.worker.adapter.out.fingerprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arguslog.worker.application.port.Fingerprinter;
import org.arguslog.worker.domain.Fingerprint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

/**
 * Pragmatic P1 grouping: hash the topmost exception's type+value, falling back to {@code message},
 * falling back to a constant. Stable enough for the dashboard to thread events into issues;
 * sophisticated grouping (frame-aware, in-app filter) lands with the symbolicator in P3.
 */
@Component
public class PayloadFingerprinter implements Fingerprinter {

  static final String UNKNOWN_HASH = "unknown";
  private static final int TITLE_MAX = 200;

  private final ObjectMapper mapper;

  public PayloadFingerprinter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Fingerprint compute(String rawPayload) {
    if (rawPayload == null || rawPayload.isBlank()) {
      return unknown();
    }
    try {
      JsonNode root = mapper.readTree(rawPayload);
      Fingerprint.Level level = Fingerprint.Level.fromString(textOrNull(root, "level"));

      JsonNode firstException = root.path("exception").path("values").path(0);
      if (!firstException.isMissingNode() && firstException.hasNonNull("type")) {
        String type = firstException.path("type").asText("Error");
        String value = firstException.path("value").asText("");
        String culprit = topFrameCulprit(firstException);
        String title = trimTo(value.isEmpty() ? type : type + ": " + value, TITLE_MAX);
        return new Fingerprint(sha256(type + "|" + value), title, culprit, level);
      }

      String message = textOrNull(root, "message");
      if (message != null && !message.isBlank()) {
        return new Fingerprint(
            sha256("message|" + message), trimTo(message, TITLE_MAX), null, level);
      }

      return unknown();
    } catch (Exception e) {
      return unknown();
    }
  }

  private static Fingerprint unknown() {
    return new Fingerprint(UNKNOWN_HASH, "Unparseable event", null, Fingerprint.Level.ERROR);
  }

  private static String textOrNull(JsonNode root, String field) {
    JsonNode node = root.get(field);
    return node == null || node.isNull() ? null : node.asText();
  }

  private static String topFrameCulprit(JsonNode exception) {
    JsonNode frames = exception.path("stacktrace").path("frames");
    if (!frames.isArray() || frames.isEmpty()) {
      return null;
    }
    // Sentry convention: last entry is the topmost frame.
    JsonNode top = frames.get(frames.size() - 1);
    String fn = top.path("function").asText(null);
    String file = top.path("filename").asText(null);
    int line = top.path("lineno").asInt(0);
    if (fn != null && file != null) {
      return line > 0 ? fn + " at " + file + ":" + line : fn + " at " + file;
    }
    if (fn != null) {
      return fn;
    }
    if (file != null) {
      return line > 0 ? file + ":" + line : file;
    }
    return null;
  }

  private static String trimTo(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static String sha256(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
