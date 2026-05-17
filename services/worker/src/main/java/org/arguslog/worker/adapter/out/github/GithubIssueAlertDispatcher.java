package org.arguslog.worker.adapter.out.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.arguslog.worker.adapter.out.AlertsProperties;
import org.arguslog.worker.application.port.AlertDispatcher;
import org.arguslog.worker.application.port.EventReadRepository;
import org.arguslog.worker.domain.Alert;
import org.arguslog.worker.domain.AlertDestination;
import org.arguslog.worker.domain.AlertDestination.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Auto-triage dispatcher: when an alert rule fires, this creates a GitHub Issue assigned to {@code
 * copilot-swe-agent} (or a user-overridden assignee). Copilot's coding agent picks assigned issues
 * up automatically and opens a draft PR.
 *
 * <p>Per-destination config (decrypted JSON in {@code alert_destinations.config_encrypted}):
 *
 * <ul>
 *   <li>{@code owner} (required) — GitHub username or org
 *   <li>{@code repo} (required) — repository name
 *   <li>{@code token} (required) — fine-grained GitHub PAT scoped to that repo with {@code
 *       Contents: read}, {@code Issues: write}, {@code Pull requests: read}
 *   <li>{@code assignee} (optional) — defaults to {@code copilot-swe-agent}
 *   <li>{@code labels} (optional array) — defaults to {@code ["arguslog-auto-triage"]}
 * </ul>
 *
 * Body is markdown — Copilot reads it as the entire input (no MCP, no follow-up tool calls). We
 * pre-bake the stack trace + breadcrumbs from the latest event into the body so the agent has
 * something concrete to grep. Failure policy matches the rest of the dispatcher fleet: log + drop
 * on every error path (no retries — P3's persistent outbox is a separate piece of work).
 */
@Component
@EnableConfigurationProperties({GithubIssueProperties.class, AlertsProperties.class})
public class GithubIssueAlertDispatcher implements AlertDispatcher {

  private static final Logger log = LoggerFactory.getLogger(GithubIssueAlertDispatcher.class);
  private static final String DEFAULT_ASSIGNEE = "copilot-swe-agent";
  private static final String DEFAULT_LABEL = "arguslog-auto-triage";
  private static final int MAX_FRAMES_IN_BODY = 20;
  private static final int MAX_BREADCRUMBS_IN_BODY = 10;

  private final GithubIssueProperties props;
  private final AlertsProperties alertsProps;
  private final EventReadRepository events;
  private final ObjectMapper mapper;
  private final HttpClient http;

  public GithubIssueAlertDispatcher(
      GithubIssueProperties props,
      AlertsProperties alertsProps,
      EventReadRepository events,
      ObjectMapper mapper) {
    this.props = props;
    this.alertsProps = alertsProps;
    this.events = events;
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
  }

  @Override
  public Kind kind() {
    return Kind.GITHUB_ISSUE;
  }

  @Override
  public void dispatch(Alert alert, AlertDestination destination) {
    Config cfg = readConfig(destination);
    if (cfg == null) return;

    Optional<JsonNode> payload =
        events.findLatestPayloadForIssue(alert.projectId(), alert.issueId());
    String body = renderBody(alert, payload.orElse(null), cfg.assignee);
    String title = renderTitle(alert);

    ObjectNode requestBody = mapper.createObjectNode();
    requestBody.put("title", title);
    requestBody.put("body", body);
    ArrayNode assignees = requestBody.putArray("assignees");
    assignees.add(cfg.assignee);
    ArrayNode labels = requestBody.putArray("labels");
    for (String label : cfg.labels) labels.add(label);

    String json;
    try {
      json = mapper.writeValueAsString(requestBody);
    } catch (JsonProcessingException e) {
      log.warn("could not encode github_issue payload: {}", e.getMessage());
      return;
    }

    URI url = URI.create(props.apiBaseUrl() + "/repos/" + cfg.owner + "/" + cfg.repo + "/issues");
    HttpRequest req =
        HttpRequest.newBuilder(url)
            .timeout(props.timeout())
            .header("Authorization", "Bearer " + cfg.token)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "arguslog/auto-triage")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();

    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 201) {
        log.info(
            "github_issue destination {} created issue in {}/{} for alert issue {} — Copilot will pick it up shortly",
            destination.id(),
            cfg.owner,
            cfg.repo,
            alert.issueId());
      } else {
        log.warn(
            "github_issue destination {} POST returned HTTP {} body={}",
            destination.id(),
            resp.statusCode(),
            truncate(resp.body()));
      }
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("github_issue destination {} threw: {}", destination.id(), e.getMessage());
    }
  }

  private Config readConfig(AlertDestination destination) {
    JsonNode root;
    try {
      root = mapper.readTree(destination.configJson());
    } catch (JsonProcessingException e) {
      log.warn(
          "github_issue destination {} config is not valid JSON: {}",
          destination.id(),
          e.getMessage());
      return null;
    }
    String owner = textOrNull(root, "owner");
    String repo = textOrNull(root, "repo");
    String token = textOrNull(root, "token");
    if (owner == null || repo == null || token == null) {
      log.warn("github_issue destination {} missing required owner/repo/token", destination.id());
      return null;
    }
    String assignee = textOrNull(root, "assignee");
    if (assignee == null) assignee = DEFAULT_ASSIGNEE;
    List<String> labels = new ArrayList<>();
    JsonNode labelsNode = root.get("labels");
    if (labelsNode != null && labelsNode.isArray()) {
      for (JsonNode label : labelsNode) {
        if (label.isTextual() && !label.asText().isBlank()) labels.add(label.asText());
      }
    }
    if (labels.isEmpty()) labels.add(DEFAULT_LABEL);
    return new Config(owner, repo, token, assignee, labels);
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode v = node.get(field);
    if (v == null || !v.isTextual() || v.asText().isBlank()) return null;
    return v.asText().trim();
  }

  private String renderTitle(Alert a) {
    String shortTitle = a.issueTitle();
    if (shortTitle.length() > 100) shortTitle = shortTitle.substring(0, 100) + "…";
    return "[Arguslog] " + a.level() + " in " + a.projectSlug() + ": " + shortTitle;
  }

  private String renderBody(Alert a, JsonNode latestPayload, String assignee) {
    String issueUrl =
        alertsProps.dashboardBaseUrl()
            + "/orgs/"
            + a.orgSlug()
            + "/projects/"
            + a.projectId()
            + "/issues/"
            + a.issueId();

    StringBuilder b = new StringBuilder(2048);
    b.append("## Arguslog auto-triage — ").append(a.issueTitle()).append("\n\n");
    b.append("A new error fired in **")
        .append(a.projectSlug())
        .append("**. This issue is assigned to **@")
        .append(assignee)
        .append(
            "** (GitHub Copilot's coding agent) — please pick it up, identify the root cause in this repository, and open a *draft* PR with the smallest plausible fix.\n\n");

    b.append("### Summary\n\n");
    b.append("| | |\n|---|---|\n");
    b.append("| Level | `").append(a.level()).append("` |\n");
    b.append("| Occurrences | ").append(a.occurrenceCount()).append(" |\n");
    b.append("| First seen | ").append(a.firstSeenAt()).append(" |\n");
    b.append("| Last seen | ").append(a.lastSeenAt()).append(" |\n");
    b.append("| Arguslog issue | [")
        .append(issueUrl)
        .append("](")
        .append(issueUrl)
        .append(") |\n\n");

    b.append("### Stack trace (most recent event)\n\n");
    String frames = extractStackFrames(latestPayload);
    b.append("```\n")
        .append(frames == null ? "No symbolicated frames available." : frames)
        .append("\n```\n\n");

    b.append("### Recent breadcrumbs\n\n");
    String breadcrumbs = extractBreadcrumbs(latestPayload);
    b.append(breadcrumbs == null ? "No breadcrumbs recorded." : breadcrumbs).append("\n\n");

    b.append("### Suggested approach\n\n");
    b.append("1. Read the stack trace. The top frame's file + line is the place to start.\n");
    b.append("2. Grep the repo for that file. Read the surrounding code.\n");
    b.append(
        "3. Form a hypothesis (what input would produce this error? what invariant was missed?).\n");
    b.append(
        "4. Make the smallest plausible change. If the cause isn't obvious from the data, open the PR as draft with `WIP:` in the title and write your uncertainty into both the PR body and a comment on this issue.\n");
    b.append(
        "5. The PR should `Closes #` this issue and link back to the Arguslog issue URL above.\n\n");

    b.append("---\n");
    b.append("*Created automatically by Arguslog.*\n");
    return b.toString();
  }

  /**
   * Extracts a readable stack trace from the latest event's payload. Handles the standard {@code
   * exception.values[0].stacktrace.frames} shape that every SDK emits. Defensive at every level — a
   * malformed payload just produces a no-data fallback, never a crash.
   */
  private String extractStackFrames(JsonNode payload) {
    if (payload == null) return null;
    JsonNode exception = payload.path("exception");
    JsonNode values = exception.path("values");
    if (!values.isArray() || values.isEmpty()) return null;
    JsonNode firstException = values.get(0);
    JsonNode frames = firstException.path("stacktrace").path("frames");
    if (!frames.isArray() || frames.isEmpty()) return null;

    StringBuilder s = new StringBuilder();
    String exType = firstException.path("type").asText("");
    String exValue = firstException.path("value").asText("");
    if (!exType.isEmpty() || !exValue.isEmpty()) {
      s.append(exType).append(exType.isEmpty() ? "" : ": ").append(exValue).append("\n");
    }
    int n = Math.min(frames.size(), MAX_FRAMES_IN_BODY);
    // SDKs emit frames in inner-to-outer order; reverse to put the call site at the top.
    for (int i = frames.size() - 1; i >= frames.size() - n; i--) {
      JsonNode frame = frames.get(i);
      String fn = frame.path("function").asText("?");
      String file = frame.path("filename").asText(frame.path("abs_path").asText("?"));
      int line = frame.path("lineno").asInt(0);
      s.append("  at ").append(fn).append(" (").append(file);
      if (line > 0) s.append(":").append(line);
      s.append(")\n");
    }
    if (frames.size() > MAX_FRAMES_IN_BODY) {
      s.append("  … ").append(frames.size() - MAX_FRAMES_IN_BODY).append(" more frames\n");
    }
    return s.toString().trim();
  }

  private String extractBreadcrumbs(JsonNode payload) {
    if (payload == null) return null;
    JsonNode breadcrumbs = payload.path("breadcrumbs").path("values");
    if (!breadcrumbs.isArray() || breadcrumbs.isEmpty()) return null;
    StringBuilder s = new StringBuilder();
    int start = Math.max(0, breadcrumbs.size() - MAX_BREADCRUMBS_IN_BODY);
    for (int i = start; i < breadcrumbs.size(); i++) {
      JsonNode crumb = breadcrumbs.get(i);
      String ts = crumb.path("timestamp").asText("");
      String category = crumb.path("category").asText("");
      String message = crumb.path("message").asText("");
      s.append("- `")
          .append(ts)
          .append("` **")
          .append(category)
          .append("**: ")
          .append(message)
          .append("\n");
    }
    return s.toString().trim();
  }

  private static String truncate(String s) {
    if (s == null) return "";
    return s.length() > 240 ? s.substring(0, 240) + "…" : s;
  }

  private record Config(
      String owner, String repo, String token, String assignee, List<String> labels) {}
}
