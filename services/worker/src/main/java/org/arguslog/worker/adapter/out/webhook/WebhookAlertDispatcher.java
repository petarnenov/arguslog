package org.arguslog.worker.adapter.out.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.arguslog.worker.application.port.AlertDispatcher;
import org.arguslog.worker.domain.Alert;
import org.arguslog.worker.domain.AlertDestination;
import org.arguslog.worker.domain.AlertDestination.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Generic JSON webhook. Per-destination config carries:
 *
 * <ul>
 *   <li>{@code url} (required) — POST target
 *   <li>{@code secret} (optional) — if set, body is HMAC-SHA256-signed and the digest is sent in
 *       {@code X-Arguslog-Signature: sha256=<hex>}. Receivers MUST verify before trusting payload.
 * </ul>
 *
 * Body is a stable structured JSON envelope — no Markdown, since webhook receivers parse not read.
 */
@Component
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookAlertDispatcher implements AlertDispatcher {

  private static final Logger log = LoggerFactory.getLogger(WebhookAlertDispatcher.class);
  private static final String HMAC_ALG = "HmacSHA256";

  private final WebhookProperties props;
  private final ObjectMapper mapper;
  private final HttpClient http;

  public WebhookAlertDispatcher(WebhookProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
  }

  @Override
  public Kind kind() {
    return Kind.WEBHOOK;
  }

  @Override
  public void dispatch(Alert alert, AlertDestination destination) {
    Config cfg = readConfig(destination);
    if (cfg == null) return;

    String body;
    try {
      body = mapper.writeValueAsString(buildEnvelope(alert));
    } catch (JsonProcessingException e) {
      log.warn("could not encode webhook payload: {}", e.getMessage());
      return;
    }

    HttpRequest.Builder req =
        HttpRequest.newBuilder(URI.create(cfg.url))
            .timeout(props.timeout())
            .header("Content-Type", "application/json")
            .header("User-Agent", "Arguslog/1.0")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

    if (cfg.secret != null) {
      String signature = sign(body, cfg.secret);
      if (signature != null) req.header("X-Arguslog-Signature", "sha256=" + signature);
    }

    try {
      HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        log.warn(
            "webhook to destination {} failed: HTTP {} body={}",
            destination.id(),
            resp.statusCode(),
            truncate(resp.body()));
      }
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("webhook to destination {} threw: {}", destination.id(), e.getMessage());
    }
  }

  private ObjectNode buildEnvelope(Alert a) {
    ObjectNode root = mapper.createObjectNode();
    ObjectNode alertNode = root.putObject("alert");
    alertNode.put("ruleId", a.ruleId());
    alertNode.put("ruleName", a.ruleName());
    alertNode.put("projectId", a.projectId());
    alertNode.put("projectSlug", a.projectSlug());
    alertNode.put("orgSlug", a.orgSlug());
    alertNode.put("issueId", a.issueId());
    alertNode.put("issueTitle", a.issueTitle());
    alertNode.put("level", a.level());
    alertNode.put("occurrenceCount", a.occurrenceCount());
    alertNode.put("firstSeenAt", a.firstSeenAt().toString());
    alertNode.put("lastSeenAt", a.lastSeenAt().toString());
    alertNode.put(
        "url",
        // Numeric projectId — the dashboard issue route doesn't accept slugs.
        props.dashboardBaseUrl()
            + "/orgs/"
            + a.orgSlug()
            + "/projects/"
            + a.projectId()
            + "/issues/"
            + a.issueId());
    return root;
  }

  private Config readConfig(AlertDestination destination) {
    JsonNode node;
    try {
      node = mapper.readTree(destination.configJson());
    } catch (JsonProcessingException e) {
      log.warn("destination {} config is not valid JSON: {}", destination.id(), e.getMessage());
      return null;
    }
    JsonNode urlNode = node.path("url");
    if (urlNode.isMissingNode() || urlNode.isNull() || !urlNode.isTextual()) {
      log.warn("destination {} missing url in config; dropping alert", destination.id());
      return null;
    }
    String url = urlNode.asText().trim();
    if (url.isEmpty()) {
      log.warn("destination {} has empty url; dropping alert", destination.id());
      return null;
    }
    String secret = null;
    JsonNode secretNode = node.path("secret");
    if (secretNode.isTextual()) {
      String s = secretNode.asText().trim();
      if (!s.isEmpty()) secret = s;
    }
    return new Config(url, secret);
  }

  private static String sign(String body, String secret) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALG);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
      byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      log.warn("hmac signing failed: {}", e.getMessage());
      return null;
    }
  }

  private static String truncate(String s) {
    if (s == null) return "";
    return s.length() > 200 ? s.substring(0, 200) + "…" : s;
  }

  private record Config(String url, String secret) {}
}
