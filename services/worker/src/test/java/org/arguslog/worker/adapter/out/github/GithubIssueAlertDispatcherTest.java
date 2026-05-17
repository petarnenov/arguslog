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

class GithubIssueAlertDispatcherTest {

  private static final String ISSUES_PATH = "/repos/acme/web/issues";
  private static final String ASSIGNEES_PATH_REGEX = "/repos/acme/web/issues/\\d+/assignees";
  private static final String ASSIGNEES_PATH_DEFAULT = "/repos/acme/web/issues/123/assignees";

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

  /** Stub both halves of the two-step flow with the same issue number coming back from create. */
  private void stubHappyPath() {
    wm.stubFor(
        post(urlPathEqualTo(ISSUES_PATH))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"number\":123}")));
    wm.stubFor(post(urlPathMatching(ASSIGNEES_PATH_REGEX)).willReturn(aResponse().withStatus(201)));
  }

  @Test
  void declaresGithubIssueKindForRouter() {
    assertThat(dispatcher.kind()).isEqualTo(Kind.GITHUB_ISSUE);
  }

  @Test
  void createPostOmitsAssigneesAndAssignsThemInFollowUpCall() throws Exception {
    stubHappyPath();

    dispatcher.dispatch(
        alert,
        githubDestination(
            "{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"ghp_test_xxxxxxxxxxxx\"}"));

    // Step 1 — create issue. Must NOT include `assignees` (GitHub rejects bot identities here
    // with 422 — that's the whole point of splitting into two calls).
    var creates = wm.findAll(postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    assertThat(creates).hasSize(1);
    assertThat(creates.get(0).getHeader("Authorization")).isEqualTo("Bearer ghp_test_xxxxxxxxxxxx");
    assertThat(creates.get(0).getHeader("Accept")).isEqualTo("application/vnd.github+json");
    assertThat(creates.get(0).getHeader("X-GitHub-Api-Version")).isEqualTo("2022-11-28");

    JsonNode createBody = mapper.readTree(creates.get(0).getBodyAsString());
    assertThat(createBody.path("title").asText())
        .contains("error")
        .contains("web")
        .contains("TypeError");
    assertThat(createBody.has("assignees")).isFalse();
    assertThat(createBody.path("labels").isArray()).isTrue();
    assertThat(createBody.path("labels").get(0).asText()).isEqualTo("arguslog-auto-triage");
    assertThat(createBody.path("body").asText())
        .contains("Arguslog auto-triage")
        .contains("https://arguslog.example/orgs/acme/projects/101/issues/42")
        .contains("@copilot-swe-agent");

    // Step 2 — assign. Targets the issue number returned by step 1 (123).
    var assigns = wm.findAll(postRequestedFor(urlPathEqualTo(ASSIGNEES_PATH_DEFAULT)));
    assertThat(assigns).hasSize(1);
    assertThat(assigns.get(0).getHeader("Authorization")).isEqualTo("Bearer ghp_test_xxxxxxxxxxxx");
    JsonNode assignBody = mapper.readTree(assigns.get(0).getBodyAsString());
    assertThat(assignBody.path("assignees").isArray()).isTrue();
    assertThat(assignBody.path("assignees").get(0).asText()).isEqualTo("copilot-swe-agent");
  }

  @Test
  void honoursOverriddenAssigneeAndLabels() throws Exception {
    stubHappyPath();

    dispatcher.dispatch(
        alert,
        githubDestination(
            "{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"t\","
                + "\"assignee\":\"jane-doe\","
                + "\"labels\":[\"bug\",\"auto\"]}"));

    JsonNode createBody =
        mapper.readTree(
            wm.findAll(postRequestedFor(urlPathEqualTo(ISSUES_PATH))).get(0).getBodyAsString());
    assertThat(createBody.path("labels").get(0).asText()).isEqualTo("bug");
    assertThat(createBody.path("labels").get(1).asText()).isEqualTo("auto");

    JsonNode assignBody =
        mapper.readTree(
            wm.findAll(postRequestedFor(urlPathEqualTo(ASSIGNEES_PATH_DEFAULT)))
                .get(0)
                .getBodyAsString());
    assertThat(assignBody.path("assignees").get(0).asText()).isEqualTo("jane-doe");
  }

  @Test
  void bodyContainsStackTraceWhenEventPayloadAvailable() throws Exception {
    stubHappyPath();
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
    stubHappyPath();
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
  }

  @Test
  void unparseableConfigDoesNotThrow() {
    dispatcher.dispatch(alert, githubDestination("not-json"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    wm.verify(0, postRequestedFor(urlPathMatching(ASSIGNEES_PATH_REGEX)));
  }

  @Test
  void createHttpErrorIsSwallowedAndAssignNotCalled() {
    wm.stubFor(post(urlPathEqualTo(ISSUES_PATH)).willReturn(aResponse().withStatus(401)));

    dispatcher.dispatch(
        alert, githubDestination("{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"t\"}"));

    wm.verify(1, postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    // No issue number to assign to — step 2 must not fire.
    wm.verify(0, postRequestedFor(urlPathMatching(ASSIGNEES_PATH_REGEX)));
  }

  @Test
  void assignHttpErrorLeavesIssueCreatedButLogsWarning() {
    // Step 1 succeeds, step 2 fails (e.g. assignee handle invalid). The issue already exists in
    // GitHub at this point — we deliberately do NOT try to delete or roll back; the operator can
    // assign manually from the UI.
    wm.stubFor(
        post(urlPathEqualTo(ISSUES_PATH))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"number\":555}")));
    wm.stubFor(
        post(urlPathEqualTo("/repos/acme/web/issues/555/assignees"))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withBody(
                        "{\"message\":\"Validation Failed\",\"errors\":[{\"message\":\"assignees foo-bot cannot be assigned to this issue\"}]}")));

    dispatcher.dispatch(
        alert,
        githubDestination(
            "{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"t\",\"assignee\":\"foo-bot\"}"));

    wm.verify(1, postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    wm.verify(1, postRequestedFor(urlPathEqualTo("/repos/acme/web/issues/555/assignees")));
    // No exception thrown; dispatch returns normally.
  }

  @Test
  void createSucceededButResponseMissingNumberSkipsAssign() {
    wm.stubFor(
        post(urlPathEqualTo(ISSUES_PATH))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}"))); // no `number` field

    dispatcher.dispatch(
        alert, githubDestination("{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"t\"}"));

    wm.verify(1, postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    wm.verify(0, postRequestedFor(urlPathMatching(ASSIGNEES_PATH_REGEX)));
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
