package org.arguslog.worker.adapter.out.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.arguslog.worker.adapter.out.AlertsProperties;
import org.arguslog.worker.application.port.EventReadRepository;
import org.arguslog.worker.domain.Alert;
import org.arguslog.worker.domain.AlertDestination;
import org.arguslog.worker.domain.AlertDestination.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two routing paths exercised here:
 *
 * <ul>
 *   <li><b>Human / collaborator assignee</b> — REST {@code POST /issues/{n}/assignees}, plus a
 *       response-body check that the configured login actually came back in {@code assignees[]}.
 *   <li><b>Bot / App assignee</b> (Copilot Cloud Agent) — GraphQL {@code suggestedActors} +
 *       {@code replaceActorsForAssignable}, because GitHub's REST endpoint silently drops bot
 *       identities (returns 2xx with the bot omitted). Discovered the hard way in production —
 *       the dispatcher used to log "created+assigned" against that lie.
 * </ul>
 */
class GithubIssueAlertDispatcherTest {

  private static final String ISSUES_PATH = "/repos/acme/web/issues";
  private static final String ASSIGNEES_PATH_REGEX = "/repos/acme/web/issues/\\d+/assignees";
  private static final String ASSIGNEES_PATH_DEFAULT = "/repos/acme/web/issues/123/assignees";
  private static final String GRAPHQL_PATH = "/graphql";
  private static final String ISSUE_NODE_ID = "I_kwDOABC123";
  private static final String COPILOT_BOT_NODE_ID = "BOT_kgDOCopilot";

  private WireMockServer wm;
  private GithubIssueAlertDispatcher dispatcher;
  private FakeEventRead events;
  private final ObjectMapper mapper = new ObjectMapper();

  private final Alert alert =
      new Alert(
          7L,
          "errors-in-prod",
          101L,
          "web",
          "acme",
          42L,
          "TypeError: x is undefined",
          "error",
          5L,
          Instant.parse("2026-05-05T11:55:00Z"),
          Instant.parse("2026-05-05T12:00:00Z"));

  @BeforeEach
  void startWireMock() {
    wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wm.start();
    events = new FakeEventRead();
    dispatcher =
        new GithubIssueAlertDispatcher(
            new GithubIssueProperties(wm.baseUrl(), Duration.ofSeconds(2)),
            new AlertsProperties("https://arguslog.example"),
            events,
            mapper);
  }

  @AfterEach
  void stopWireMock() {
    wm.stop();
  }

  /**
   * Default REST happy-path stubs — used by tests with a HUMAN assignee. The /issues stub returns
   * both `number` (REST identity) and `node_id` (GraphQL identity, populated even for the human
   * path so the create-step's null check passes uniformly). The /assignees stub mimics GitHub's
   * "issue with current assignees" response shape, with the requested login present.
   */
  private void stubHappyPathForHuman(String assigneeLogin) {
    wm.stubFor(
        post(urlPathEqualTo(ISSUES_PATH))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"number\":123,\"node_id\":\"" + ISSUE_NODE_ID + "\"}")));
    wm.stubFor(
        post(urlPathMatching(ASSIGNEES_PATH_REGEX))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"number\":123,\"assignees\":[{\"login\":\""
                            + assigneeLogin
                            + "\"}]}")));
  }

  /**
   * Default GraphQL happy-path stubs — used by tests with a BOT assignee. Two stubs: the
   * suggestedActors query returns the bot's node ID; the mutation returns the post-assignment
   * issue. Both go through the same `/graphql` endpoint, so WireMock matches by request body to
   * pick which response to serve (cheaper than wiring distinct paths).
   */
  private void stubHappyPathForBot(String botLogin) {
    wm.stubFor(
        post(urlPathEqualTo(ISSUES_PATH))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"number\":123,\"node_id\":\"" + ISSUE_NODE_ID + "\"}")));
    wm.stubFor(
        post(urlPathEqualTo(GRAPHQL_PATH))
            .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("suggestedActors"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"data\":{\"repository\":{\"suggestedActors\":{\"nodes\":["
                            + "{\"__typename\":\"Bot\",\"id\":\""
                            + COPILOT_BOT_NODE_ID
                            + "\",\"login\":\""
                            + botLogin
                            + "\"}]}}}}")));
    wm.stubFor(
        post(urlPathEqualTo(GRAPHQL_PATH))
            .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("replaceActorsForAssignable"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"data\":{\"replaceActorsForAssignable\":{\"assignable\":{\"number\":123,\"assignees\":{\"nodes\":[{\"login\":\""
                            + botLogin
                            + "\"}]}}}}}")));
  }

  @Test
  void declaresGithubIssueKindForRouter() {
    assertThat(dispatcher.kind()).isEqualTo(Kind.GITHUB_ISSUE);
  }

  @Test
  void humanAssigneeUsesRestAndVerifiesResponseContainsTheLogin() throws Exception {
    stubHappyPathForHuman("jane-doe");

    dispatcher.dispatch(
        alert,
        githubDestination(
            "{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"ghp_test\",\"assignee\":\"jane-doe\"}"));

    // Step 1 — create issue with title/body/labels but NO assignees.
    var creates = wm.findAll(postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    assertThat(creates).hasSize(1);
    JsonNode createBody = mapper.readTree(creates.get(0).getBodyAsString());
    assertThat(createBody.has("assignees")).isFalse();
    assertThat(createBody.path("labels").get(0).asText()).isEqualTo("arguslog-auto-triage");

    // Step 2 — REST POST /assignees with the human login.
    var assigns = wm.findAll(postRequestedFor(urlPathEqualTo(ASSIGNEES_PATH_DEFAULT)));
    assertThat(assigns).hasSize(1);
    JsonNode assignBody = mapper.readTree(assigns.get(0).getBodyAsString());
    assertThat(assignBody.path("assignees").get(0).asText()).isEqualTo("jane-doe");

    // Bot path must NOT fire when assignee is human — that would be a useless extra round-trip
    // and would mask cases where the human isn't actually a collaborator.
    wm.verify(0, postRequestedFor(urlPathEqualTo(GRAPHQL_PATH)));
  }

  @Test
  void copilotSweAgentRoutesThroughGraphQLNotRest() throws Exception {
    // copilot-swe-agent is the historical handle for Copilot Cloud Agent and is the default
    // assignee on github_issue destinations. REST silently drops it (returns 2xx without
    // actually assigning); GraphQL replaceActorsForAssignable is the only path that works.
    stubHappyPathForBot("copilot-swe-agent");

    dispatcher.dispatch(
        alert,
        githubDestination(
            "{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"ghp_test\"}")); // default assignee

    // Step 1 — REST create issue (still REST; only the assignment step diverges).
    wm.verify(1, postRequestedFor(urlPathEqualTo(ISSUES_PATH)));

    // Step 2 — GraphQL only, REST /assignees skipped entirely.
    wm.verify(0, postRequestedFor(urlPathMatching(ASSIGNEES_PATH_REGEX)));
    var graphqlCalls = wm.findAll(postRequestedFor(urlPathEqualTo(GRAPHQL_PATH)));
    assertThat(graphqlCalls).hasSize(2); // 1 query + 1 mutation

    // Query body asks for suggestedActors with CAN_BE_ASSIGNED capability — that's the only
    // GitHub API path that returns Bot identities alongside Users.
    String queryBody = graphqlCalls.get(0).getBodyAsString();
    assertThat(queryBody).contains("suggestedActors").contains("CAN_BE_ASSIGNED");

    // Mutation body refers to the issue's node_id from step 1 and the bot's node ID from the
    // query response — neither of which the REST endpoint accepts.
    String mutationBody = graphqlCalls.get(1).getBodyAsString();
    assertThat(mutationBody).contains("replaceActorsForAssignable").contains(ISSUE_NODE_ID).contains(COPILOT_BOT_NODE_ID);
  }

  @Test
  void copilotCapitalCRoutesThroughGraphQLToo() throws Exception {
    // GitHub's UI displays the assignable bot as "Copilot" (capital C). Both that and the
    // historical lowercase `copilot-swe-agent` must hit the GraphQL path — operators copying
    // either handle from the GitHub UI should get the same auto-triage behaviour.
    stubHappyPathForBot("Copilot");

    dispatcher.dispatch(
        alert,
        githubDestination(
            "{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"ghp_test\",\"assignee\":\"Copilot\"}"));

    wm.verify(0, postRequestedFor(urlPathMatching(ASSIGNEES_PATH_REGEX)));
    wm.verify(2, postRequestedFor(urlPathEqualTo(GRAPHQL_PATH)));
  }

  @Test
  void restPathWarnsWhenAssigneeIsSilentlyDropped() {
    // GitHub returns 2xx but the resulting `assignees` array doesn't contain our requested
    // login. Pre-fix this used to log "created+assigned" against a lie; now it logs WARN so
    // the operator notices and can debug (typically: PAT lacks repo:write, or login isn't a
    // collaborator).
    wm.stubFor(
        post(urlPathEqualTo(ISSUES_PATH))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"number\":123,\"node_id\":\"" + ISSUE_NODE_ID + "\"}")));
    wm.stubFor(
        post(urlPathMatching(ASSIGNEES_PATH_REGEX))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"number\":123,\"assignees\":[]}"))); // empty array = silent drop

    dispatcher.dispatch(
        alert,
        githubDestination(
            "{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"ghp_test\",\"assignee\":\"jane-doe\"}"));

    // Both calls fire; the verification happens on the response body, not the status code.
    wm.verify(1, postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    wm.verify(1, postRequestedFor(urlPathEqualTo(ASSIGNEES_PATH_DEFAULT)));
    // No exception thrown; dispatcher returns normally after the warn-log.
  }

  @Test
  void graphQLPathWarnsWhenBotIsNotInSuggestedActors() {
    // suggestedActors response doesn't contain the requested bot — operator hasn't enabled
    // Copilot Cloud Agent for this repo. Dispatcher logs WARN with the recovery hint instead
    // of silently failing OR firing the mutation against a phantom actor id.
    wm.stubFor(
        post(urlPathEqualTo(ISSUES_PATH))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"number\":123,\"node_id\":\"" + ISSUE_NODE_ID + "\"}")));
    wm.stubFor(
        post(urlPathEqualTo(GRAPHQL_PATH))
            .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("suggestedActors"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"data\":{\"repository\":{\"suggestedActors\":{\"nodes\":[]}}}}")));

    dispatcher.dispatch(
        alert, githubDestination("{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"ghp_test\"}"));

    wm.verify(1, postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    var graphqlCalls = wm.findAll(postRequestedFor(urlPathEqualTo(GRAPHQL_PATH)));
    assertThat(graphqlCalls).hasSize(1); // only the query; mutation skipped when no actor found
  }

  @Test
  void graphQLPathWarnsWhenMutationReturnsErrors() {
    // suggestedActors resolves cleanly but replaceActorsForAssignable returns a `errors` array
    // (e.g. permission denied, repo archived). Dispatcher logs WARN and leaves the issue
    // created — the operator must assign manually.
    wm.stubFor(
        post(urlPathEqualTo(ISSUES_PATH))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"number\":123,\"node_id\":\"" + ISSUE_NODE_ID + "\"}")));
    wm.stubFor(
        post(urlPathEqualTo(GRAPHQL_PATH))
            .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("suggestedActors"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"data\":{\"repository\":{\"suggestedActors\":{\"nodes\":[{\"__typename\":\"Bot\",\"id\":\""
                            + COPILOT_BOT_NODE_ID
                            + "\",\"login\":\"copilot-swe-agent\"}]}}}}")));
    wm.stubFor(
        post(urlPathEqualTo(GRAPHQL_PATH))
            .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("replaceActorsForAssignable"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"errors\":[{\"message\":\"Resource not accessible by integration\"}]}")));

    dispatcher.dispatch(
        alert, githubDestination("{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"ghp_test\"}"));

    wm.verify(2, postRequestedFor(urlPathEqualTo(GRAPHQL_PATH))); // query + mutation both fired
  }

  @Test
  void bodyContainsStackTraceWhenEventPayloadAvailable() throws Exception {
    stubHappyPathForBot("copilot-swe-agent");
    events.setPayload(
        mapper.readTree(
            """
            {
              "exception": {
                "values": [{
                  "type": "TypeError",
                  "value": "x is undefined",
                  "stacktrace": {
                    "frames": [
                      { "function": "main", "filename": "index.js", "lineno": 5 },
                      { "function": "render", "filename": "app.js",   "lineno": 42 }
                    ]
                  }
                }]
              },
              "breadcrumbs": {
                "values": [
                  { "timestamp": "2026-05-05T11:59:00Z", "category": "nav", "message": "/dashboard" },
                  { "timestamp": "2026-05-05T11:59:30Z", "category": "click", "message": "button.save" }
                ]
              }
            }
            """));

    dispatcher.dispatch(
        alert, githubDestination("{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"t\"}"));

    JsonNode body =
        mapper.readTree(
            wm.findAll(postRequestedFor(urlPathEqualTo(ISSUES_PATH))).get(0).getBodyAsString());
    String md = body.path("body").asText();
    assertThat(md).contains("render").contains("app.js:42");
    assertThat(md).contains("button.save");
  }

  @Test
  void bodyHasFallbackWhenNoEventPayload() throws Exception {
    stubHappyPathForBot("copilot-swe-agent");
    events.clear();

    dispatcher.dispatch(
        alert, githubDestination("{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"t\"}"));

    JsonNode body =
        mapper.readTree(
            wm.findAll(postRequestedFor(urlPathEqualTo(ISSUES_PATH))).get(0).getBodyAsString());
    String md = body.path("body").asText();
    assertThat(md).contains("No symbolicated frames available.");
    assertThat(md).contains("No breadcrumbs recorded.");
  }

  @Test
  void missingRequiredFieldsDropTheMessage() {
    dispatcher.dispatch(alert, githubDestination("{\"owner\":\"acme\"}"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    wm.verify(0, postRequestedFor(urlPathMatching(ASSIGNEES_PATH_REGEX)));
    wm.verify(0, postRequestedFor(urlPathEqualTo(GRAPHQL_PATH)));
  }

  @Test
  void unparseableConfigDoesNotThrow() {
    dispatcher.dispatch(alert, githubDestination("not-json"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    wm.verify(0, postRequestedFor(urlPathMatching(ASSIGNEES_PATH_REGEX)));
    wm.verify(0, postRequestedFor(urlPathEqualTo(GRAPHQL_PATH)));
  }

  @Test
  void createHttpErrorIsSwallowedAndAssignNotCalled() {
    wm.stubFor(post(urlPathEqualTo(ISSUES_PATH)).willReturn(aResponse().withStatus(401)));

    dispatcher.dispatch(
        alert, githubDestination("{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"t\"}"));

    wm.verify(1, postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    // No issue number/nodeId to assign — neither step 2 path may fire.
    wm.verify(0, postRequestedFor(urlPathMatching(ASSIGNEES_PATH_REGEX)));
    wm.verify(0, postRequestedFor(urlPathEqualTo(GRAPHQL_PATH)));
  }

  @Test
  void createSucceededButResponseMissingNumberSkipsAssign() {
    wm.stubFor(
        post(urlPathEqualTo(ISSUES_PATH))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}"))); // no `number` field, no `node_id`

    dispatcher.dispatch(
        alert, githubDestination("{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"t\"}"));

    wm.verify(1, postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    wm.verify(0, postRequestedFor(urlPathMatching(ASSIGNEES_PATH_REGEX)));
    wm.verify(0, postRequestedFor(urlPathEqualTo(GRAPHQL_PATH)));
  }

  @Test
  void restPathAssignHttpErrorLeavesIssueCreated() {
    // Step 1 succeeds, step 2 returns a real error (not silent-drop). Used to be the common
    // case for `copilot-swe-agent` — now only happens for misconfigured humans (invalid PAT
    // scope, locked-down repo). Dispatcher must not throw; the issue stays created.
    wm.stubFor(
        post(urlPathEqualTo(ISSUES_PATH))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"number\":555,\"node_id\":\"" + ISSUE_NODE_ID + "\"}")));
    wm.stubFor(
        post(urlPathEqualTo("/repos/acme/web/issues/555/assignees"))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withBody(
                        "{\"message\":\"Validation Failed\",\"errors\":[{\"message\":\"assignees jane-doe cannot be assigned to this issue\"}]}")));

    dispatcher.dispatch(
        alert,
        githubDestination(
            "{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"t\",\"assignee\":\"jane-doe\"}"));

    wm.verify(1, postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    wm.verify(1, postRequestedFor(urlPathEqualTo("/repos/acme/web/issues/555/assignees")));
  }

  private static AlertDestination githubDestination(String configJson) {
    return new AlertDestination(99L, 1L, Kind.GITHUB_ISSUE, "auto-triage", configJson);
  }

  /** In-memory stand-in for {@link EventReadRepository}. */
  private static final class FakeEventRead implements EventReadRepository {
    private JsonNode payload;

    @Override
    public Optional<JsonNode> findLatestPayloadForIssue(long projectId, long issueId) {
      return Optional.ofNullable(payload);
    }

    void setPayload(JsonNode payload) {
      this.payload = payload;
    }

    void clear() {
      this.payload = null;
    }
  }
}
