package org.arguslog.api.slack.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.arguslog.api.slack.adapter.in.web.dto.SlackCommandPayload;
import org.arguslog.api.slack.adapter.in.web.dto.SlackCommandResponse;
import org.arguslog.api.slack.application.SlackCommandDispatcher;
import org.arguslog.api.slack.application.SlackSigningVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slack-facing controller. Sits OUTSIDE the JWT/PAT auth chain — Slack signed requests can't
 * carry our bearer tokens. {@code /api/v1/slack/**} is allow-listed in SecurityConfig; the
 * signing-secret check inside this method is the only authentication.
 */
// arguslog.slack.enabled=false opts the entire Slack stack out — every test that doesn't want
// a DataSource autowire failure sets this. Production / staging leave it unset → default true.
@RestController
@ConditionalOnProperty(name = "arguslog.slack.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping(
    value = "/api/v1/slack",
    produces = MediaType.APPLICATION_JSON_VALUE)
public class SlackController {

  private static final Logger log = LoggerFactory.getLogger(SlackController.class);

  private final SlackSigningVerifier verifier;
  private final SlackCommandDispatcher dispatcher;

  public SlackController(SlackSigningVerifier verifier, SlackCommandDispatcher dispatcher) {
    this.verifier = verifier;
    this.dispatcher = dispatcher;
  }

  /**
   * Slack POSTs form-encoded payloads here. Spring's body-parser would consume the InputStream
   * for `@RequestParam`, but the signing verifier needs the raw bytes to recompute the HMAC.
   * Read once from {@link HttpServletRequest#getReader} and parse manually.
   */
  @PostMapping(
      value = "/commands",
      consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  public ResponseEntity<SlackCommandResponse> commands(HttpServletRequest request) throws IOException {
    String body = readBody(request);
    String timestamp = request.getHeader("X-Slack-Request-Timestamp");
    String signature = request.getHeader("X-Slack-Signature");
    if (!verifier.verify(timestamp, body, signature)) {
      log.warn(
          "rejecting Slack command with bad signature (timestamp={}, signature_prefix={})",
          timestamp,
          signature == null ? "null" : signature.substring(0, Math.min(8, signature.length())));
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    Map<String, String> form = parseFormUrlencoded(body);
    SlackCommandPayload payload =
        new SlackCommandPayload(
            form.getOrDefault("team_id", ""),
            form.getOrDefault("team_domain", ""),
            form.getOrDefault("channel_id", ""),
            form.getOrDefault("user_id", ""),
            form.getOrDefault("command", ""),
            form.getOrDefault("text", ""),
            form.getOrDefault("response_url", ""));

    SlackCommandResponse response = dispatcher.dispatch(payload);
    return ResponseEntity.ok(response);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static String readBody(HttpServletRequest request) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (var reader = request.getReader()) {
      char[] buf = new char[4096];
      int n;
      while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
    }
    return sb.toString();
  }

  /** Tiny form-urlencoded parser — Spring's MultiValueMap would also work but pulls in the
   *  servlet filter chain we deliberately bypass for this endpoint. */
  private static Map<String, String> parseFormUrlencoded(String body) {
    Map<String, String> out = new HashMap<>();
    if (body == null || body.isEmpty()) return out;
    for (String pair : body.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) continue;
      String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
      String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      out.put(key, val);
    }
    return out;
  }
}
