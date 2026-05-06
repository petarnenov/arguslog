package org.arguslog.worker.adapter.out.slack;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.arguslog.worker.application.port.AlertDispatcher;
import org.arguslog.worker.domain.Alert;
import org.arguslog.worker.domain.AlertDestination;
import org.arguslog.worker.domain.AlertDestination.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * POSTs a Slack-mrkdwn message to a per-destination incoming webhook. No global token — the webhook
 * URL itself authorizes the post. Failures (4xx, 5xx, timeout) are logged and dropped to match the
 * rest of the dispatch pipeline.
 */
@Component
@EnableConfigurationProperties(SlackProperties.class)
public class SlackAlertDispatcher implements AlertDispatcher {

  private static final Logger log = LoggerFactory.getLogger(SlackAlertDispatcher.class);
  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

  private final SlackProperties props;
  private final ObjectMapper mapper;
  private final HttpClient http;

  public SlackAlertDispatcher(SlackProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
  }

  @Override
  public Kind kind() {
    return Kind.SLACK;
  }

  @Override
  public void dispatch(Alert alert, AlertDestination destination) {
    String webhookUrl = readWebhookUrl(destination);
    if (webhookUrl == null) {
      log.warn(
          "destination {} ({}) missing webhookUrl in config; dropping alert",
          destination.id(),
          destination.name());
      return;
    }

    String body;
    try {
      ObjectNode payload = mapper.createObjectNode();
      payload.put("text", renderMessage(alert));
      body = mapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      log.warn("could not encode slack payload: {}", e.getMessage());
      return;
    }

    HttpRequest req =
        HttpRequest.newBuilder(URI.create(webhookUrl))
            .timeout(props.timeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        log.warn(
            "slack webhook to destination {} failed: HTTP {} body={}",
            destination.id(),
            resp.statusCode(),
            truncate(resp.body()));
      }
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("slack webhook to destination {} threw: {}", destination.id(), e.getMessage());
    }
  }

  private String readWebhookUrl(AlertDestination destination) {
    try {
      JsonNode node = mapper.readTree(destination.configJson());
      JsonNode url = node.path("webhookUrl");
      if (url.isMissingNode() || url.isNull() || !url.isTextual()) return null;
      String raw = url.asText().trim();
      return raw.isEmpty() ? null : raw;
    } catch (JsonProcessingException e) {
      log.warn("destination {} config is not valid JSON: {}", destination.id(), e.getMessage());
      return null;
    }
  }

  private String renderMessage(Alert a) {
    String emoji = emojiFor(a.level());
    String url =
        props.dashboardBaseUrl()
            + "/orgs/"
            + a.orgSlug()
            + "/projects/"
            + a.projectSlug()
            + "/issues/"
            + a.issueId();
    return emoji
        + " *"
        + a.level()
        + "* in *"
        + a.projectSlug()
        + "*\n"
        + a.issueTitle()
        + "\n"
        + a.occurrenceCount()
        + "x · first seen "
        + ISO.format(a.firstSeenAt())
        + "\n<"
        + url
        + "|Open in Arguslog>"
        + "\n_rule: "
        + a.ruleName()
        + "_";
  }

  private static String emojiFor(String level) {
    if (level == null) return ":bell:";
    return switch (level.toLowerCase(Locale.ROOT)) {
      case "fatal" -> ":no_entry:";
      case "error" -> ":rotating_light:";
      case "warning" -> ":warning:";
      case "info" -> ":information_source:";
      default -> ":bell:";
    };
  }

  private static String truncate(String s) {
    if (s == null) return "";
    return s.length() > 200 ? s.substring(0, 200) + "…" : s;
  }
}
