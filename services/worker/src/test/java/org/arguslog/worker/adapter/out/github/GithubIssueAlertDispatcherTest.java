package org.arguslog.worker.adapter.out.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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

  @Test
  void declaresGithubIssueKindForRouter() {
    assertThat(dispatcher.kind()).isEqualTo(Kind.GITHUB_ISSUE);
  }

  @Test
  void createsIssueWithDefaultAssigneeAndLabel() throws Exception {
    wm.stubFor(post(urlPathEqualTo(ISSUES_PATH)).willReturn(aResponse().withStatus(201)));

    dispatcher.dispatch(
        alert,
        githubDestination(
            "{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"ghp_test_xxxxxxxxxxxx\"}"));

    var requests = wm.findAll(postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    assertThat(requests).hasSize(1);
    assertThat(requests.get(0).getHeader("Authorization"))
        .isEqualTo("Bearer ghp_test_xxxxxxxxxxxx");
    assertThat(requests.get(0).getHeader("Accept")).isEqualTo("application/vnd.github+json");
    assertThat(requests.get(0).getHeader("X-GitHub-Api-Version")).isEqualTo("2022-11-28");

    JsonNode body = mapper.readTree(requests.get(0).getBodyAsString());
    assertThat(body.path("title").asText()).contains("error").contains("web").contains("TypeError");
    assertThat(body.path("assignees").isArray()).isTrue();
    assertThat(body.path("assignees").get(0).asText()).isEqualTo("copilot-swe-agent");
    assertThat(body.path("labels").isArray()).isTrue();
    assertThat(body.path("labels").get(0).asText()).isEqualTo("arguslog-auto-triage");
    assertThat(body.path("body").asText())
        .contains("Arguslog auto-triage")
        .contains("https://arguslog.example/orgs/acme/projects/101/issues/42")
        .contains("@copilot-swe-agent");
  }

  @Test
  void honoursOverriddenAssigneeAndLabels() throws Exception {
    wm.stubFor(post(urlPathEqualTo(ISSUES_PATH)).willReturn(aResponse().withStatus(201)));

    dispatcher.dispatch(
        alert,
        githubDestination(
            "{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"t\","
                + "\"assignee\":\"jane-doe\","
                + "\"labels\":[\"bug\",\"auto\"]}"));

    var requests = wm.findAll(postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
    JsonNode body = mapper.readTree(requests.get(0).getBodyAsString());
    assertThat(body.path("assignees").get(0).asText()).isEqualTo("jane-doe");
    assertThat(body.path("labels").get(0).asText()).isEqualTo("bug");
    assertThat(body.path("labels").get(1).asText()).isEqualTo("auto");
  }

  @Test
  void bodyContainsStackTraceWhenEventPayloadAvailable() throws Exception {
    wm.stubFor(post(urlPathEqualTo(ISSUES_PATH)).willReturn(aResponse().withStatus(201)));
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
    wm.stubFor(post(urlPathEqualTo(ISSUES_PATH)).willReturn(aResponse().withStatus(201)));
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
  }

  @Test
  void unparseableConfigDoesNotThrow() {
    dispatcher.dispatch(alert, githubDestination("not-json"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
  }

  @Test
  void httpErrorIsSwallowedNotThrown() {
    wm.stubFor(post(urlPathEqualTo(ISSUES_PATH)).willReturn(aResponse().withStatus(401)));
    dispatcher.dispatch(
        alert, githubDestination("{\"owner\":\"acme\",\"repo\":\"web\",\"token\":\"t\"}"));
    wm.verify(1, postRequestedFor(urlPathEqualTo(ISSUES_PATH)));
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
