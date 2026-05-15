package org.arguslog.api.slack.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.arguslog.api.application.IssueTriageUseCase;
import org.arguslog.api.domain.Issue;
import org.arguslog.api.security.OrgContext;
import org.arguslog.api.slack.application.SlackSigningVerifier;
import org.arguslog.api.slack.application.port.SlackWorkspaceRepository;
import org.arguslog.api.slack.domain.SlackWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles Block Kit "interactivity" callbacks — every button click in an alert message Slack
 * sent on our behalf round-trips through here. Slack POSTs {@code application/x-www-form-
 * urlencoded} with a single {@code payload=<json>} field, signs the whole body with the same
 * HMAC as slash commands, and expects a 200 within 3 seconds (anything else surfaces an
 * "app didn't respond" toast to the user).
 *
 * <p>Action_id format produced by {@code SlackAlertDispatcher} (worker side):
 * {@code <op>:<issueId>}. The org / project are resolved from the Slack {@code team.id}
 * carried in the payload — same workspace lookup as slash commands.
 *
 * <p>We acknowledge the click with an inline 200 carrying an ephemeral text block; the user
 * sees the confirmation under the alert message. The original alert stays so the team has
 * the audit trail without us having to chase response-url updates.
 */
@RestController
@ConditionalOnProperty(name = "arguslog.slack.enabled", havingValue = "true", matchIfMissing = true)
public class SlackInteractivityController {

  private static final Logger log = LoggerFactory.getLogger(SlackInteractivityController.class);

  private final SlackSigningVerifier verifier;
  private final SlackWorkspaceRepository workspaces;
  private final IssueTriageUseCase triage;
  private final ObjectMapper mapper;
  private final HttpClient http;

  @Autowired
  public SlackInteractivityController(
      SlackSigningVerifier verifier,
      SlackWorkspaceRepository workspaces,
      IssueTriageUseCase triage,
      ObjectMapper mapper) {
    this(verifier, workspaces, triage, mapper, HttpClient.newHttpClient());
  }

  /** Test ctor — swap the HTTP client for a WireMock-backed one. */
  SlackInteractivityController(
      SlackSigningVerifier verifier,
      SlackWorkspaceRepository workspaces,
      IssueTriageUseCase triage,
      ObjectMapper mapper,
      HttpClient http) {
    this.verifier = verifier;
    this.workspaces = workspaces;
    this.triage = triage;
    this.mapper = mapper;
    this.http = http;
  }

  @PostMapping(
      value = "/api/v1/slack/interactivity",
      consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> handle(HttpServletRequest request) throws IOException {
    String body = readBody(request);
    String timestamp = request.getHeader("X-Slack-Request-Timestamp");
    String signature = request.getHeader("X-Slack-Signature");
    if (!verifier.verify(timestamp, body, signature)) {
      log.warn("rejecting Slack interactivity payload with bad signature");
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    String payloadJson = extractPayloadField(body);
    if (payloadJson == null) {
      log.warn("interactivity payload missing 'payload' field");
      return ResponseEntity.badRequest().build();
    }

    JsonNode payload;
    try {
      payload = mapper.readTree(payloadJson);
    } catch (JsonProcessingException e) {
      log.warn("interactivity payload not valid JSON: {}", e.getMessage());
      return ResponseEntity.badRequest().build();
    }

    String teamId = payload.path("team").path("id").asText("");
    Optional<SlackWorkspace> workspaceOpt = workspaces.findActiveByTeamId(teamId);
    if (workspaceOpt.isEmpty()) {
      log.warn("interactivity for unknown/inactive team {}", teamId);
      return ResponseEntity.ok("{\"text\":\"This workspace is no longer connected to Arguslog.\"}");
    }
    SlackWorkspace workspace = workspaceOpt.get();

    JsonNode action = payload.path("actions").path(0);
    String actionId = action.path("action_id").asText("");
    String responseUrl = payload.path("response_url").asText("");

    Result outcome = handleAction(workspace, actionId);
    if (outcome.message != null && !responseUrl.isBlank()) {
      sendEphemeral(responseUrl, outcome.message);
    }
    return ResponseEntity.ok().build();
  }

  private Result handleAction(SlackWorkspace workspace, String actionId) {
    String[] parts = actionId.split(":", 2);
    if (parts.length != 2) return Result.skip();
    String op = parts[0];
    long issueId;
    try {
      issueId = Long.parseLong(parts[1]);
    } catch (NumberFormatException e) {
      return Result.skip();
    }

    Long projectId = workspace.defaultProjectId();
    if (projectId == null) {
      return Result.text("⚠️ No default project set for this workspace — pick one in the dashboard.");
    }

    OrgContext.set(workspace.orgId());
    try {
      return switch (op) {
        case "resolve" -> applyStatus(workspace, projectId, issueId, Issue.Status.RESOLVED, "✅ Resolved");
        case "ignore" -> applyStatus(workspace, projectId, issueId, Issue.Status.IGNORED, "🔕 Ignored");
        case "open" -> Result.skip(); // url-button — Slack handles navigation client-side
        default -> Result.text("Unknown action `" + op + "`.");
      };
    } finally {
      OrgContext.clear();
    }
  }

  private Result applyStatus(
      SlackWorkspace workspace, long projectId, long issueId, Issue.Status status, String verb) {
    Optional<Issue> updated = triage.updateStatus(workspace.orgId(), projectId, issueId, status);
    if (updated.isEmpty()) {
      return Result.text("Issue #" + issueId + " not found.");
    }
    return Result.text(verb + " issue #" + issueId + ".");
  }

  private void sendEphemeral(String responseUrl, String text) {
    String body = "{\"response_type\":\"ephemeral\",\"replace_original\":false,\"text\":"
        + mapper.valueToTree(text)
        + "}";
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(responseUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      http.send(req, HttpResponse.BodyHandlers.discarding());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("failed to POST ephemeral confirmation to response_url: {}", e.getMessage());
    }
  }

  private static String readBody(HttpServletRequest request) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (var reader = request.getReader()) {
      char[] buf = new char[4096];
      int n;
      while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
    }
    return sb.toString();
  }

  /** Extracts {@code payload=...} from a form-urlencoded body; returns null if missing. */
  static String extractPayloadField(String body) {
    if (body == null || body.isEmpty()) return null;
    for (String pair : body.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) continue;
      String key = pair.substring(0, eq);
      if ("payload".equals(key)) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private record Result(String message) {
    static Result text(String msg) {
      return new Result(msg);
    }

    static Result skip() {
      return new Result(null);
    }
  }
}
