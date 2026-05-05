package org.arguslog.worker.adapter.out.telegram;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import org.arguslog.worker.application.port.AlertDispatcher;
import org.arguslog.worker.domain.Alert;
import org.arguslog.worker.domain.AlertDestination;
import org.arguslog.worker.domain.AlertDestination.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Sends a Markdown-formatted message to a Telegram chat via the Bot API. Per-destination config is
 * a JSON object with at least {@code chatId}; everything else (parse mode, threading) is bot-level.
 *
 * <p>Failure policy (matches the rest of the dispatch pipeline): every failure is logged and
 * swallowed. Telegram 4xx is permanent (bad chat id, bot kicked), 5xx is transient — but with no
 * persistent outbox in P3 we drop both. P3 #5 brings throttling, not retries.
 */
@Component
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramAlertDispatcher implements AlertDispatcher {

  private static final Logger log = LoggerFactory.getLogger(TelegramAlertDispatcher.class);
  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

  private final TelegramProperties props;
  private final ObjectMapper mapper;
  private final HttpClient http;

  public TelegramAlertDispatcher(TelegramProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
    if (!props.configured()) {
      log.warn(
          "argus.alerts.telegram.bot-token is empty — Telegram dispatch will log-and-drop until set");
    }
  }

  @Override
  public Kind kind() {
    return Kind.TELEGRAM;
  }

  @Override
  public void dispatch(Alert alert, AlertDestination destination) {
    if (!props.configured()) {
      log.warn(
          "telegram bot token unset; dropping alert ruleId={} issueId={}",
          alert.ruleId(),
          alert.issueId());
      return;
    }
    String chatId = readChatId(destination);
    if (chatId == null) {
      log.warn(
          "destination {} ({}) missing chatId in config; dropping alert",
          destination.id(),
          destination.name());
      return;
    }
    String text = renderMessage(alert);
    String body;
    try {
      ObjectNode payload = mapper.createObjectNode();
      payload.put("chat_id", chatId);
      payload.put("text", text);
      payload.put("parse_mode", "Markdown");
      payload.put("disable_web_page_preview", true);
      body = mapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      log.warn("could not encode telegram payload: {}", e.getMessage());
      return;
    }

    URI uri = URI.create(props.apiBaseUrl() + "/bot" + props.botToken() + "/sendMessage");
    HttpRequest req =
        HttpRequest.newBuilder(uri)
            .timeout(props.timeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        log.warn(
            "telegram sendMessage to chat {} failed: HTTP {} body={}",
            chatId,
            resp.statusCode(),
            truncate(resp.body()));
      }
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("telegram sendMessage threw for chat {}: {}", chatId, e.getMessage());
    }
  }

  private String readChatId(AlertDestination destination) {
    try {
      JsonNode node = mapper.readTree(destination.configJson());
      JsonNode chat = node.path("chatId");
      if (chat.isMissingNode() || chat.isNull()) return null;
      return chat.asText();
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
    String firstSeen = ISO.format(a.firstSeenAt());
    return emoji
        + " *"
        + escape(a.level())
        + "* in *"
        + escape(a.projectSlug())
        + "*\n"
        + escape(a.issueTitle())
        + "\n"
        + a.occurrenceCount()
        + "x · first seen "
        + firstSeen
        + "\n"
        + url
        + "\n_rule: "
        + escape(a.ruleName())
        + "_";
  }

  private static String emojiFor(String level) {
    if (level == null) return "🔔";
    return switch (level.toLowerCase(java.util.Locale.ROOT)) {
      case "fatal" -> "🛑";
      case "error" -> "🚨";
      case "warning" -> "⚠️";
      case "info" -> "ℹ️";
      default -> "🔔";
    };
  }

  // Telegram Markdown is permissive but * and _ are control chars; escape to keep the layout sane.
  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("_", "\\_").replace("*", "\\*").replace("`", "\\`");
  }

  private static String truncate(String s) {
    if (s == null) return "";
    return s.length() > 200 ? s.substring(0, 200) + "…" : s;
  }

  // Visible to tests so they can override the timeout via constructor without messing with props.
  Duration timeout() {
    return props.timeout();
  }
}
