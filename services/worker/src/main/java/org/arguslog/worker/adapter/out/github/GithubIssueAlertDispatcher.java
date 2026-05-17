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
import java.util.Set;
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
 * <p>Two-step POST: GitHub's {@code POST /repos/{owner}/{repo}/issues} endpoint validates the
 * {@code assignees} array against the human-collaborator list and rejects bot/app identities
 * (including {@code copilot-swe-agent}) with HTTP 422. The dedicated sub-resource {@code POST
 * /repos/{owner}/{repo}/issues/{n}/assignees} is more permissive and accepts those handles. We
 * always create-then-assign in two calls, regardless of whether the configured assignee is a bot or
 * a human, so the dispatch path stays uniform.
 *
 * <p>Body is markdown — Copilot reads it as the entire input (no MCP, no follow-up tool calls). We
 * pre-bake the stack trace + breadcrumbs from the latest event into the body so the agent has
 * something concrete to grep. Failure policy matches the rest of the dispatcher fleet: log + drop
 * on every error path (no retries — P3's persistent outbox is a separate piece of work). If step 1
 * (create) fails, we log and drop. If step 2 (assign) fails, the issue still exists in GitHub — we
 * log a WARN noting the assignee call failed so the operator can pick it up manually.
 */
@Component
@EnableConfigurationProperties({GithubIssueProperties.class, AlertsProperties.class})
public class GithubIssueAlertDispatcher implements AlertDispatcher {

  private static final Logger log = LoggerFactory.getLogger(GithubIssueAlertDispatcher.class);
  private static final String DEFAULT_ASSIGNEE = "copilot-swe-agent";
  private static final String DEFAULT_LABEL = "arguslog-auto-triage";
  private static final int MAX_FRAMES_IN_BODY = 20;
  private static final int MAX_BREADCRUMBS_IN_BODY = 10;

  /**
   * Logins (case-insensitive) that GitHub's REST {@code POST /issues/{n}/assignees} silently
   * drops because they're GitHub Apps / bots rather than human users / collaborators. The REST
   * endpoint returns 2xx with the bot omitted from the {@code assignees} array — which used to
   * make our dispatcher log "created+assigned" even when nothing actually happened. For these
   * handles we go straight to GraphQL ({@code replaceActorsForAssignable}), which accepts bot /
   * App node IDs natively.
   *
   * <p>If GitHub adds another bot handle that exhibits the same silent-drop behaviour, append it
   * here. The REST-then-verify fallback below (see {@link #assignViaRest}) will also log a WARN
   * when an unrecognised handle gets silently dropped, so the operator notices.
   */
  private static final Set<String> KNOWN_BOT_LOGINS_LOWERCASE =
      Set.of("copilot-swe-agent", "copilot");

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

    CreatedIssue created = createIssue(destination.id(), cfg, title, body);
    if (created == null) return; // step-1 failure already logged

    if (isKnownBot(cfg.assignee)) {
      // GitHub's REST `/issues/{n}/assignees` endpoint silently drops bot / App identities (it
      // returns 2xx with the bot omitted from the response's `assignees` array). The dispatcher
      // used to log "created+assigned" on that lie. For known bots we skip REST entirely and
      // use GraphQL `replaceActorsForAssignable`, which is the same path GitHub's UI uses when
      // the operator clicks "Assign to Agent" / picks Copilot from the assignees dropdown.
      assignViaGraphQL(destination.id(), cfg, created, alert.issueId());
    } else {
      assignViaRest(destination.id(), cfg, created, alert.issueId());
    }
  }

  private static boolean isKnownBot(String assignee) {
    return KNOWN_BOT_LOGINS_LOWERCASE.contains(assignee.toLowerCase(java.util.Locale.ROOT));
  }

  /**
   * Step 1: {@code POST /repos/{owner}/{repo}/issues} with the rendered title, body, and labels but
   * NO assignees — see class javadoc. Returns the newly-created issue (REST `number` for the
   * human-readable issue URL + GraphQL {@code node_id} for {@code replaceActorsForAssignable}),
   * or {@code null} on failure (already logged).
   */
  private CreatedIssue createIssue(long destinationId, Config cfg, String title, String body) {
    ObjectNode requestBody = mapper.createObjectNode();
    requestBody.put("title", title);
    requestBody.put("body", body);
    ArrayNode labels = requestBody.putArray("labels");
    for (String label : cfg.labels) labels.add(label);

    String json;
    try {
      json = mapper.writeValueAsString(requestBody);
    } catch (JsonProcessingException e) {
      log.warn("could not encode github_issue create payload: {}", e.getMessage());
      return null;
    }

    URI url = URI.create(props.apiBaseUrl() + "/repos/" + cfg.owner + "/" + cfg.repo + "/issues");
    HttpRequest req =
        githubRequest(url, cfg.token)
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();

    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 201) {
        log.warn(
            "github_issue destination {} create POST returned HTTP {} body={}",
            destinationId,
            resp.statusCode(),
            truncate(resp.body()));
        return null;
      }
      JsonNode created = mapper.readTree(resp.body());
      int number = created.path("number").asInt(0);
      String nodeId = created.path("node_id").asText("");
      if (number == 0) {
        log.warn(
            "github_issue destination {} create response missing 'number' field; body={}",
            destinationId,
            truncate(resp.body()));
        return null;
      }
      if (nodeId.isEmpty() && isKnownBot(cfg.assignee)) {
        // GraphQL needs node_id; humans don't. Only blocker if we're heading to the bot path.
        log.warn(
            "github_issue destination {} create response missing 'node_id'; GraphQL bot assignment will be skipped for issue #{}",
            destinationId,
            number);
        return null;
      }
      return new CreatedIssue(number, nodeId);
    } catch (JsonProcessingException e) {
      log.warn(
          "github_issue destination {} create response not valid JSON: {}",
          destinationId,
          e.getMessage());
      return null;
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("github_issue destination {} create threw: {}", destinationId, e.getMessage());
      return null;
    }
  }

  /**
   * Human / collaborator assignee — REST {@code POST /issues/{n}/assignees}. Verifies the
   * configured login actually appears in the response's {@code assignees} array; GitHub silently
   * drops handles it can't honour (App identities, non-collaborators), and we'd rather log WARN
   * than the misleading "created+assigned" INFO the dispatcher previously emitted.
   */
  private void assignViaRest(
      long destinationId, Config cfg, CreatedIssue issue, long alertIssueId) {
    ObjectNode requestBody = mapper.createObjectNode();
    ArrayNode assignees = requestBody.putArray("assignees");
    assignees.add(cfg.assignee);

    String json;
    try {
      json = mapper.writeValueAsString(requestBody);
    } catch (JsonProcessingException e) {
      log.warn("could not encode github_issue assignees payload: {}", e.getMessage());
      return;
    }

    URI url =
        URI.create(
            props.apiBaseUrl()
                + "/repos/"
                + cfg.owner
                + "/"
                + cfg.repo
                + "/issues/"
                + issue.number()
                + "/assignees");
    HttpRequest req =
        githubRequest(url, cfg.token)
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();

    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        log.warn(
            "github_issue destination {} created issue #{} in {}/{} but assignee POST failed HTTP {} body={} — operator can assign {} manually",
            destinationId,
            issue.number(),
            cfg.owner,
            cfg.repo,
            resp.statusCode(),
            truncate(resp.body()),
            cfg.assignee);
        return;
      }
      if (!responseContainsAssignee(resp.body(), cfg.assignee)) {
        // GitHub returned 2xx but our assignee isn't in the resulting `assignees` array. This
        // is the silent-drop pattern that affected `copilot-swe-agent` before the GraphQL path
        // existed. If we hit this for a non-bot handle, the operator's PAT either lacks
        // repo:write or the assignee isn't a collaborator on the repo.
        log.warn(
            "github_issue destination {} created issue #{} in {}/{} but assignee {} was SILENTLY DROPPED by GitHub (not in response.assignees). Operator must assign manually, and may need to add {} as a repo collaborator first.",
            destinationId,
            issue.number(),
            cfg.owner,
            cfg.repo,
            cfg.assignee,
            cfg.assignee);
        return;
      }
      log.info(
          "github_issue destination {} created+assigned issue #{} in {}/{} to {} (REST) for alert issue {}",
          destinationId,
          issue.number(),
          cfg.owner,
          cfg.repo,
          cfg.assignee,
          alertIssueId);
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn(
          "github_issue destination {} created issue #{} but assignee POST threw: {}",
          destinationId,
          issue.number(),
          e.getMessage());
    }
  }

  /**
   * Bot / App assignee — GraphQL {@code replaceActorsForAssignable}.
   *
   * <p>Two-phase: (1) query the repo's {@code suggestedActors} list for an actor whose
   * case-insensitive login matches {@link Config#assignee}, then (2) mutate the issue's actor
   * set. {@code suggestedActors} returns both Users and Bots; the App identity behind Copilot
   * Cloud Agent only shows up there, never in the REST {@code /assignees} endpoint's allowed
   * input set.
   *
   * <p>Operates against the same {@code apiBaseUrl} as REST — GitHub's GraphQL endpoint is the
   * REST host's {@code /graphql} path.
   */
  private void assignViaGraphQL(
      long destinationId, Config cfg, CreatedIssue issue, long alertIssueId) {
    String actorId = findActorNodeId(destinationId, cfg);
    if (actorId == null) return; // already logged

    String mutation =
        "mutation($id:ID!,$actorIds:[ID!]!){"
            + "replaceActorsForAssignable(input:{assignableId:$id,actorIds:$actorIds}){"
            + "assignable{... on Issue{number assignees(first:5){nodes{login}}}}"
            + "}}";

    ObjectNode variables = mapper.createObjectNode();
    variables.put("id", issue.nodeId());
    ArrayNode actors = variables.putArray("actorIds");
    actors.add(actorId);

    JsonNode resp = postGraphQL(destinationId, cfg.token, mutation, variables, "assign mutation");
    if (resp == null) return; // already logged

    if (resp.has("errors") && resp.get("errors").isArray() && !resp.get("errors").isEmpty()) {
      log.warn(
          "github_issue destination {} created issue #{} in {}/{} but GraphQL replaceActorsForAssignable returned errors: {}",
          destinationId,
          issue.number(),
          cfg.owner,
          cfg.repo,
          truncate(resp.get("errors").toString()));
      return;
    }

    log.info(
        "github_issue destination {} created+assigned issue #{} in {}/{} to {} (GraphQL) for alert issue {} — Copilot will pick it up shortly",
        destinationId,
        issue.number(),
        cfg.owner,
        cfg.repo,
        cfg.assignee,
        alertIssueId);
  }

  /**
   * Resolves the configured bot login to its GraphQL node ID by paging through the repo's
   * {@code suggestedActors} list (capped at 100 to avoid runaway). Returns {@code null} (and
   * logs WARN) if the bot isn't in the suggested-actors set — typically because the operator
   * hasn't enabled Copilot Cloud Agent for the repo yet.
   */
  private String findActorNodeId(long destinationId, Config cfg) {
    String query =
        "query($owner:String!,$repo:String!){"
            + "repository(owner:$owner,name:$repo){"
            + "suggestedActors(capabilities:[CAN_BE_ASSIGNED],first:100){"
            + "nodes{__typename ... on Bot{id login} ... on User{id login}}"
            + "}}}";
    ObjectNode variables = mapper.createObjectNode();
    variables.put("owner", cfg.owner);
    variables.put("repo", cfg.repo);

    JsonNode resp = postGraphQL(destinationId, cfg.token, query, variables, "suggestedActors query");
    if (resp == null) return null;

    if (resp.has("errors") && resp.get("errors").isArray() && !resp.get("errors").isEmpty()) {
      log.warn(
          "github_issue destination {} suggestedActors query returned GraphQL errors: {}",
          destinationId,
          truncate(resp.get("errors").toString()));
      return null;
    }

    JsonNode nodes =
        resp.path("data").path("repository").path("suggestedActors").path("nodes");
    if (!nodes.isArray()) {
      log.warn(
          "github_issue destination {} suggestedActors response missing data.repository.suggestedActors.nodes; body={}",
          destinationId,
          truncate(resp.toString()));
      return null;
    }
    String wanted = cfg.assignee.toLowerCase(java.util.Locale.ROOT);
    for (JsonNode node : nodes) {
      String login = node.path("login").asText("");
      if (login.toLowerCase(java.util.Locale.ROOT).equals(wanted)) {
        String id = node.path("id").asText("");
        if (!id.isEmpty()) return id;
      }
    }
    log.warn(
        "github_issue destination {} could not find {} in suggestedActors for {}/{}. Enable Copilot Cloud Agent for the repo (Repo Settings → Copilot → Cloud agent), or update the destination's assignee to a real collaborator login.",
        destinationId,
        cfg.assignee,
        cfg.owner,
        cfg.repo);
    return null;
  }

  /**
   * Generic POST against {@code <apiBaseUrl>/graphql}. Returns the parsed response body or
   * {@code null} on transport / HTTP / parse failure (each path logs WARN with a context tag so
   * the failing call site is identifiable).
   */
  private JsonNode postGraphQL(
      long destinationId, String token, String graphql, JsonNode variables, String tag) {
    ObjectNode body = mapper.createObjectNode();
    body.put("query", graphql);
    body.set("variables", variables);

    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      log.warn(
          "github_issue destination {} could not encode GraphQL {} payload: {}",
          destinationId,
          tag,
          e.getMessage());
      return null;
    }

    URI url = URI.create(props.apiBaseUrl() + "/graphql");
    HttpRequest req =
        githubRequest(url, token)
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();

    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        log.warn(
            "github_issue destination {} GraphQL {} returned HTTP {} body={}",
            destinationId,
            tag,
            resp.statusCode(),
            truncate(resp.body()));
        return null;
      }
      return mapper.readTree(resp.body());
    } catch (JsonProcessingException e) {
      log.warn(
          "github_issue destination {} GraphQL {} response not valid JSON: {}",
          destinationId,
          tag,
          e.getMessage());
      return null;
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn(
          "github_issue destination {} GraphQL {} threw: {}", destinationId, tag, e.getMessage());
      return null;
    }
  }

  /**
   * Parses the REST {@code /assignees} response (an Issue object with the post-assignment
   * {@code assignees} array) and checks the configured login is in it case-insensitively. Any
   * parse failure is treated as a silent-drop — better one false WARN than missing a real one.
   */
  private boolean responseContainsAssignee(String responseBody, String wantedLogin) {
    try {
      JsonNode root = mapper.readTree(responseBody);
      JsonNode arr = root.path("assignees");
      if (!arr.isArray()) return false;
      String wanted = wantedLogin.toLowerCase(java.util.Locale.ROOT);
      for (JsonNode node : arr) {
        if (wanted.equals(node.path("login").asText("").toLowerCase(java.util.Locale.ROOT))) {
          return true;
        }
      }
      return false;
    } catch (JsonProcessingException e) {
      return false;
    }
  }

  /** Shared header set for both step 1 and step 2 calls. */
  private HttpRequest.Builder githubRequest(URI url, String token) {
    return HttpRequest.newBuilder(url)
        .timeout(props.timeout())
        .header("Authorization", "Bearer " + token)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "arguslog/auto-triage");
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

  /**
   * Output of {@link #createIssue}. Carries both the REST integer {@code number} (for the
   * dashboard / log URL) and the GraphQL global {@code node_id} (for
   * {@code replaceActorsForAssignable}'s {@code assignableId} input). For human-assignee
   * destinations the {@code nodeId} is unused.
   */
  private record CreatedIssue(int number, String nodeId) {}
}
