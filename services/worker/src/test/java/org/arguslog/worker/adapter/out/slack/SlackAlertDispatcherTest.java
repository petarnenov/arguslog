package org.arguslog.worker.adapter.out.slack;

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
import org.arguslog.worker.domain.Alert;
import org.arguslog.worker.domain.AlertDestination;
import org.arguslog.worker.domain.AlertDestination.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlackAlertDispatcherTest {

  private static final String HOOK_PATH = "/services/T00/B00/XXX";

  private WireMockServer wm;
  private SlackAlertDispatcher dispatcher;
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
    dispatcher =
        new SlackAlertDispatcher(
            new SlackProperties("https://arguslog.example", Duration.ofSeconds(2)), mapper);
  }

  @AfterEach
  void stopWireMock() {
    wm.stop();
  }

  @Test
  void postsTextPayloadToWebhookUrl() throws Exception {
    wm.stubFor(
        post(urlPathEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(200).withBody("ok")));

    dispatcher.dispatch(
        alert, slackDestination("{\"webhookUrl\":\"" + wm.baseUrl() + HOOK_PATH + "\"}"));

    var requests = wm.findAll(postRequestedFor(urlPathEqualTo(HOOK_PATH)));
    assertThat(requests).hasSize(1);
    JsonNode body = mapper.readTree(requests.get(0).getBodyAsString());
    String text = body.path("text").asText();
    assertThat(text)
        .contains("error")
        .contains("web")
        .contains("TypeError")
        .contains("https://arguslog.example/orgs/acme/projects/101/issues/42")
        .contains("rule: errors-in-prod");
  }

  @Test
  void missingWebhookUrlDropsTheMessage() {
    dispatcher.dispatch(alert, slackDestination("{}"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(HOOK_PATH)));
  }

  @Test
  void unparseableConfigDoesNotThrow() {
    dispatcher.dispatch(alert, slackDestination("not-json"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(HOOK_PATH)));
  }

  @Test
  void httpErrorIsSwallowedNotThrown() {
    wm.stubFor(
        post(urlPathEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(500).withBody("nope")));
    dispatcher.dispatch(
        alert, slackDestination("{\"webhookUrl\":\"" + wm.baseUrl() + HOOK_PATH + "\"}"));
    wm.verify(1, postRequestedFor(urlPathEqualTo(HOOK_PATH)));
  }

  @Test
  void timeoutIsSwallowed() {
    wm.stubFor(
        post(urlPathEqualTo(HOOK_PATH))
            .willReturn(aResponse().withStatus(200).withFixedDelay(5_000)));
    SlackAlertDispatcher fast =
        new SlackAlertDispatcher(
            new SlackProperties("https://arguslog.example", Duration.ofMillis(150)), mapper);
    fast.dispatch(alert, slackDestination("{\"webhookUrl\":\"" + wm.baseUrl() + HOOK_PATH + "\"}"));
    // Reaching this line is the assertion: dispatcher absorbed the timeout.
  }

  @Test
  void declaresSlackKindForRouter() {
    assertThat(dispatcher.kind()).isEqualTo(Kind.SLACK);
  }

  private static AlertDestination slackDestination(String configJson) {
    return new AlertDestination(99L, 1L, Kind.SLACK, "eng-alerts", configJson);
  }
}
