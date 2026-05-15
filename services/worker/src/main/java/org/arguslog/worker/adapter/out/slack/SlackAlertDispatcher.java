package org.arguslog.worker.adapter.out.slack;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
      payload.put("text", renderMessage(alert)); // mrkdwn fallback for notification preview
      payload.set("blocks", renderBlocks(alert));
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

  /**
   * Block Kit message with action buttons. The action_id format is {@code <op>:<issueId>}; the
   * api-side interactivity handler routes by op and resolves the org/project from the Slack
   * team_id carried in the payload, so the button payload itself stays minimal.
   */
  private ArrayNode renderBlocks(Alert a) {
    ArrayNode blocks = mapper.createArrayNode();
    String url = issueUrl(a);
    String header =
        emojiFor(a.level())
            + " *<"
            + url
            + "|"
            + escape(a.issueTitle())
            + ">*\n"
            + a.level()
            + " in *"
            + a.projectSlug()
            + "* · "
            + a.occurrenceCount()
            + "× · first seen "
            + ISO.format(a.firstSeenAt())
            + "\n_rule: "
            + a.ruleName()
            + "_";

    ObjectNode section = mapper.createObjectNode();
    section.put("type", "section");
    ObjectNode text = mapper.createObjectNode();
    text.put("type", "mrkdwn");
    text.put("text", header);
    section.set("text", text);
    blocks.add(section);

    ObjectNode actions = mapper.createObjectNode();
    actions.put("type", "actions");
    ArrayNode elements = mapper.createArrayNode();
    elements.add(button("Resolve", "resolve:" + a.issueId(), "primary"));
    elements.add(button("Ignore", "ignore:" + a.issueId(), null));
    elements.add(linkButton("Open in Arguslog", url));
    actions.set("elements", elements);
    blocks.add(actions);

    return blocks;
  }

  private ObjectNode button(String label, String actionId, String style) {
    ObjectNode b = mapper.createObjectNode();
    b.put("type", "button");
    ObjectNode text = mapper.createObjectNode();
    text.put("type", "plain_text");
    text.put("text", label);
    b.set("text", text);
    b.put("action_id", actionId);
    b.put("value", actionId);
    if (style != null) b.put("style", style);
    return b;
  }

  private ObjectNode linkButton(String label, String url) {
    ObjectNode b = mapper.createObjectNode();
    b.put("type", "button");
    ObjectNode text = mapper.createObjectNode();
    text.put("type", "plain_text");
    text.put("text", label);
    b.set("text", text);
    b.put("url", url);
    // Slack requires action_id on every button — even url-only ones — and rejects the message
    // if two share it, so we suffix the issue id to keep it unique per alert.
    b.put("action_id", "open:noop:" + System.nanoTime());
    return b;
  }

  private String issueUrl(Alert a) {
    return props.dashboardBaseUrl()
        + "/orgs/"
        + a.orgSlug()
        + "/projects/"
        + a.projectSlug()
        + "/issues/"
        + a.issueId();
  }

  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
