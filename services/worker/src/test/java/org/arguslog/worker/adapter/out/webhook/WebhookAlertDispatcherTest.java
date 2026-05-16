package org.arguslog.worker.adapter.out.webhook;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.arguslog.worker.adapter.out.AlertsProperties;
import org.arguslog.worker.domain.Alert;
import org.arguslog.worker.domain.AlertDestination;
import org.arguslog.worker.domain.AlertDestination.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookAlertDispatcherTest {

  private static final String HOOK_PATH = "/arguslog/inbound";

  private WireMockServer wm;
  private WebhookAlertDispatcher dispatcher;
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
        new WebhookAlertDispatcher(
            new WebhookProperties(Duration.ofSeconds(2)),
            new AlertsProperties("https://arguslog.example"),
            mapper);
  }

  @AfterEach
  void stopWireMock() {
    wm.stop();
  }

  @Test
  void postsStructuredEnvelopeWithAlertFields() throws Exception {
    wm.stubFor(post(urlPathEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(202)));

    dispatcher.dispatch(
        alert, webhookDestination("{\"url\":\"" + wm.baseUrl() + HOOK_PATH + "\"}"));

    var requests = wm.findAll(postRequestedFor(urlPathEqualTo(HOOK_PATH)));
    assertThat(requests).hasSize(1);
    JsonNode body = mapper.readTree(requests.get(0).getBodyAsString());
    JsonNode a = body.path("alert");
    assertThat(a.path("ruleId").asLong()).isEqualTo(7L);
    assertThat(a.path("ruleName").asText()).isEqualTo("errors-in-prod");
    assertThat(a.path("issueId").asLong()).isEqualTo(42L);
    assertThat(a.path("issueTitle").asText()).isEqualTo("TypeError: x is undefined");
    assertThat(a.path("level").asText()).isEqualTo("error");
    assertThat(a.path("occurrenceCount").asLong()).isEqualTo(5L);
    assertThat(a.path("url").asText())
        .isEqualTo("https://arguslog.example/orgs/acme/projects/101/issues/42");
    assertThat(requests.get(0).getHeader("X-Arguslog-Signature")).isNull();
  }

  @Test
  void hmacSha256SignatureMatchesWhenSecretProvided() throws Exception {
    String secret = "shhhh";
    wm.stubFor(post(urlPathEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(200)));

    dispatcher.dispatch(
        alert,
        webhookDestination(
            "{\"url\":\"" + wm.baseUrl() + HOOK_PATH + "\",\"secret\":\"" + secret + "\"}"));

    var requests = wm.findAll(postRequestedFor(urlPathEqualTo(HOOK_PATH)));
    assertThat(requests).hasSize(1);
    String header = requests.get(0).getHeader("X-Arguslog-Signature");
    assertThat(header).startsWith("sha256=");
    String expected = "sha256=" + hmacHex(secret, requests.get(0).getBodyAsString());
    assertThat(header).isEqualTo(expected);
  }

  @Test
  void emptySecretSkipsSignature() {
    wm.stubFor(post(urlPathEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(200)));
    dispatcher.dispatch(
        alert,
        webhookDestination("{\"url\":\"" + wm.baseUrl() + HOOK_PATH + "\",\"secret\":\"   \"}"));
    var requests = wm.findAll(postRequestedFor(urlPathEqualTo(HOOK_PATH)));
    assertThat(requests).hasSize(1);
    assertThat(requests.get(0).getHeader("X-Arguslog-Signature")).isNull();
  }

  @Test
  void missingUrlDropsTheMessage() {
    dispatcher.dispatch(alert, webhookDestination("{}"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(HOOK_PATH)));
  }

  @Test
  void unparseableConfigDoesNotThrow() {
    dispatcher.dispatch(alert, webhookDestination("not-json"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(HOOK_PATH)));
  }

  @Test
  void httpErrorIsSwallowedNotThrown() {
    wm.stubFor(post(urlPathEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(500)));
    dispatcher.dispatch(
        alert, webhookDestination("{\"url\":\"" + wm.baseUrl() + HOOK_PATH + "\"}"));
    wm.verify(1, postRequestedFor(urlPathEqualTo(HOOK_PATH)));
  }

  @Test
  void declaresWebhookKindForRouter() {
    assertThat(dispatcher.kind()).isEqualTo(Kind.WEBHOOK);
  }

  private static String hmacHex(String secret, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  private static AlertDestination webhookDestination(String configJson) {
    return new AlertDestination(99L, 1L, Kind.WEBHOOK, "ops-hook", configJson);
  }
}
