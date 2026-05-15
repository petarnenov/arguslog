package org.arguslog.worker.adapter.out.email;

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

class ResendEmailDispatcherTest {

  private static final String SEND_PATH = "/emails";

  private WireMockServer wm;
  private ResendEmailDispatcher dispatcher;
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
        new ResendEmailDispatcher(
            new EmailProperties(
                wm.baseUrl(),
                "re_test_key",
                "alerts@arguslog.example",
                "https://arguslog.example",
                Duration.ofSeconds(2)),
            mapper);
  }

  @AfterEach
  void stopWireMock() {
    wm.stop();
  }

  @Test
  void postsResendPayloadWithBearerAuthAndSubjectBody() throws Exception {
    wm.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"e_x\"}")));

    dispatcher.dispatch(alert, emailDestination("{\"to\":\"ops@example.com\"}"));

    var requests = wm.findAll(postRequestedFor(urlPathEqualTo(SEND_PATH)));
    assertThat(requests).hasSize(1);
    assertThat(requests.get(0).getHeader("Authorization")).isEqualTo("Bearer re_test_key");
    JsonNode body = mapper.readTree(requests.get(0).getBodyAsString());
    assertThat(body.path("from").asText()).isEqualTo("alerts@arguslog.example");
    assertThat(body.path("to").get(0).asText()).isEqualTo("ops@example.com");
    assertThat(body.path("subject").asText())
        .contains("[Arguslog]")
        .contains("error")
        .contains("web")
        .contains("TypeError");
    String text = body.path("text").asText();
    assertThat(text)
        .contains("TypeError")
        .contains("Project:    web")
        .contains("https://arguslog.example/orgs/acme/projects/web/issues/42")
        .contains("rule: errors-in-prod");
  }

  @Test
  void postsResendPayloadForArrayOfRecipients() throws Exception {
    wm.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"e_x\"}")));

    dispatcher.dispatch(
        alert, emailDestination("{\"to\":[\"ops@example.com\",\"sre@example.com\"]}"));

    var requests = wm.findAll(postRequestedFor(urlPathEqualTo(SEND_PATH)));
    assertThat(requests).hasSize(1);
    JsonNode toArr = mapper.readTree(requests.get(0).getBodyAsString()).path("to");
    assertThat(toArr.isArray()).isTrue();
    assertThat(toArr).hasSize(2);
    assertThat(toArr.get(0).asText()).isEqualTo("ops@example.com");
    assertThat(toArr.get(1).asText()).isEqualTo("sre@example.com");
  }

  @Test
  void missingToDropsTheMessage() {
    dispatcher.dispatch(alert, emailDestination("{}"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  @Test
  void emptyArrayDropsTheMessage() {
    dispatcher.dispatch(alert, emailDestination("{\"to\":[]}"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  @Test
  void unparseableConfigDoesNotThrow() {
    dispatcher.dispatch(alert, emailDestination("not-json"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  @Test
  void httpErrorIsSwallowedNotThrown() {
    wm.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"bad key\"}")));
    dispatcher.dispatch(alert, emailDestination("{\"to\":\"ops@example.com\"}"));
    wm.verify(1, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  @Test
  void emptyApiKeyDropsTheMessageWithoutCallingResend() {
    ResendEmailDispatcher unconfigured =
        new ResendEmailDispatcher(
            new EmailProperties(
                wm.baseUrl(),
                "",
                "alerts@arguslog.example",
                "https://arguslog.example",
                Duration.ofSeconds(2)),
            mapper);
    unconfigured.dispatch(alert, emailDestination("{\"to\":\"ops@example.com\"}"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  @Test
  void declaresEmailKindForRouter() {
    assertThat(dispatcher.kind()).isEqualTo(Kind.EMAIL);
  }

  private static AlertDestination emailDestination(String configJson) {
    return new AlertDestination(99L, 1L, Kind.EMAIL, "ops-email", configJson);
  }
}
