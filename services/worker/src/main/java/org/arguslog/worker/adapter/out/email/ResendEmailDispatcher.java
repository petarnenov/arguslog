package org.arguslog.worker.adapter.out.email;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Sends alert emails via the Resend HTTP API (no SDK — keeps worker dep-free). Plain-text body to
 * stay well under spam-filter thresholds. Per-destination config is just {@code {to: "..."}};
 * {@code from} is global so a single Argus install can verify one sender domain.
 */
@Component
@EnableConfigurationProperties(EmailProperties.class)
public class ResendEmailDispatcher implements AlertDispatcher {

  private static final Logger log = LoggerFactory.getLogger(ResendEmailDispatcher.class);
  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

  private final EmailProperties props;
  private final ObjectMapper mapper;
  private final HttpClient http;

  public ResendEmailDispatcher(EmailProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
    if (!props.configured()) {
      log.warn("argus.alerts.email.api-key is empty — email dispatch will log-and-drop until set");
    }
  }

  @Override
  public Kind kind() {
    return Kind.EMAIL;
  }

  @Override
  public void dispatch(Alert alert, AlertDestination destination) {
    if (!props.configured()) {
      log.warn(
          "resend api key unset; dropping alert ruleId={} issueId={}",
          alert.ruleId(),
          alert.issueId());
      return;
    }
    String to = readTo(destination);
    if (to == null) {
      log.warn(
          "destination {} ({}) missing 'to' in config; dropping alert",
          destination.id(),
          destination.name());
      return;
    }

    String body;
    try {
      ObjectNode payload = mapper.createObjectNode();
      payload.put("from", props.from());
      payload.putArray("to").add(to);
      payload.put("subject", renderSubject(alert));
      payload.put("text", renderBody(alert));
      body = mapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      log.warn("could not encode resend payload: {}", e.getMessage());
      return;
    }

    HttpRequest req =
        HttpRequest.newBuilder(URI.create(props.apiBaseUrl() + "/emails"))
            .timeout(props.timeout())
            .header("Authorization", "Bearer " + props.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        log.warn(
            "resend send to {} failed: HTTP {} body={}",
            to,
            resp.statusCode(),
            truncate(resp.body()));
      }
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("resend send to {} threw: {}", to, e.getMessage());
    }
  }

  private String readTo(AlertDestination destination) {
    try {
      JsonNode node = mapper.readTree(destination.configJson());
      JsonNode to = node.path("to");
      if (to.isMissingNode() || to.isNull() || !to.isTextual()) return null;
      String raw = to.asText().trim();
      return raw.isEmpty() ? null : raw;
    } catch (JsonProcessingException e) {
      log.warn("destination {} config is not valid JSON: {}", destination.id(), e.getMessage());
      return null;
    }
  }

  private String renderSubject(Alert a) {
    return "[Argus] " + a.level() + " in " + a.projectSlug() + ": " + a.issueTitle();
  }

  private String renderBody(Alert a) {
    String url =
        props.dashboardBaseUrl()
            + "/orgs/"
            + a.orgSlug()
            + "/projects/"
            + a.projectSlug()
            + "/issues/"
            + a.issueId();
    return a.issueTitle()
        + "\n\n"
        + "Project:    "
        + a.projectSlug()
        + " ("
        + a.orgSlug()
        + ")\n"
        + "Level:      "
        + a.level()
        + "\n"
        + "Occurrences: "
        + a.occurrenceCount()
        + "\n"
        + "First seen: "
        + ISO.format(a.firstSeenAt())
        + "\n"
        + "Last seen:  "
        + ISO.format(a.lastSeenAt())
        + "\n\n"
        + "Open: "
        + url
        + "\n\n"
        + "— rule: "
        + a.ruleName();
  }

  private static String truncate(String s) {
    if (s == null) return "";
    return s.length() > 200 ? s.substring(0, 200) + "…" : s;
  }
}
