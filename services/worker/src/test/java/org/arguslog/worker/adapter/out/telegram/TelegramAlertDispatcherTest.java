package org.arguslog.worker.adapter.out.telegram;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import java.time.Duration;
import java.time.Instant;
import org.arguslog.worker.domain.Alert;
import org.arguslog.worker.domain.AlertDestination;
import org.arguslog.worker.domain.AlertDestination.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelegramAlertDispatcherTest {

  private static final String BOT_TOKEN = "test-token";
  private static final String SEND_PATH = "/bot" + BOT_TOKEN + "/sendMessage";

  private WireMockServer wm;
  private TelegramAlertDispatcher dispatcher;
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
        new TelegramAlertDispatcher(
            new TelegramProperties(
                wm.baseUrl(), BOT_TOKEN, "https://argus.example", Duration.ofSeconds(2)),
            mapper);
  }

  @AfterEach
  void stopWireMock() {
    wm.stop();
  }

  @Test
  void postsMarkdownPayloadWithDestinationChatId() throws Exception {
    wm.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

    dispatcher.dispatch(alert, telegramDestination("{\"chatId\":\"-1001234\"}"));

    RequestPatternBuilder pattern = postRequestedFor(urlPathEqualTo(SEND_PATH));
    var requests = wm.findAll(pattern);
    assertThat(requests).hasSize(1);
    JsonNode body = mapper.readTree(requests.get(0).getBodyAsString());
    assertThat(body.path("chat_id").asText()).isEqualTo("-1001234");
    assertThat(body.path("parse_mode").asText()).isEqualTo("Markdown");
    String text = body.path("text").asText();
    assertThat(text)
        .contains("error")
        .contains("web")
        .contains("TypeError")
        .contains("https://argus.example/orgs/acme/projects/web/issues/42")
        .contains("rule: errors-in-prod")
        .contains("5x");
  }

  @Test
  void missingChatIdDropsTheMessageWithoutCallingTelegram() {
    dispatcher.dispatch(alert, telegramDestination("{}"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  @Test
  void unparseableConfigDoesNotThrow() {
    dispatcher.dispatch(alert, telegramDestination("not-json"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  @Test
  void httpErrorIsSwallowedNotThrown() {
    wm.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withBody(
                        "{\"ok\":false,\"error_code\":403,\"description\":\"bot blocked\"}")));

    // Must not throw; failure policy is log-and-drop.
    dispatcher.dispatch(alert, telegramDestination("{\"chatId\":\"-1001234\"}"));

    wm.verify(1, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  @Test
  void timeoutIsSwallowed() {
    wm.stubFor(
        post(urlPathEqualTo(SEND_PATH))
            .willReturn(aResponse().withStatus(200).withFixedDelay(5_000)));
    TelegramAlertDispatcher fast =
        new TelegramAlertDispatcher(
            new TelegramProperties(
                wm.baseUrl(), BOT_TOKEN, "https://argus.example", Duration.ofMillis(150)),
            mapper);

    fast.dispatch(alert, telegramDestination("{\"chatId\":\"-1\"}"));
    // Reaching this line means the dispatcher absorbed the timeout instead of bubbling it.
  }

  @Test
  void emptyBotTokenDropsTheMessage() {
    TelegramAlertDispatcher unconfigured =
        new TelegramAlertDispatcher(
            new TelegramProperties(
                wm.baseUrl(), "", "https://argus.example", Duration.ofSeconds(2)),
            mapper);
    unconfigured.dispatch(alert, telegramDestination("{\"chatId\":\"-1\"}"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(SEND_PATH)));
  }

  @Test
  void escapesMarkdownControlCharsInProjectAndTitle() throws Exception {
    wm.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(200)));
    Alert tricky =
        new Alert(
            1L,
            "rule_with_underscores",
            101L,
            "web*name",
            "acme",
            42L,
            "fail _at_ `boot`",
            "error",
            1L,
            Instant.parse("2026-05-05T12:00:00Z"),
            Instant.parse("2026-05-05T12:00:00Z"));
    dispatcher.dispatch(tricky, telegramDestination("{\"chatId\":\"-1\"}"));

    var requests = wm.findAll(postRequestedFor(urlPathEqualTo(SEND_PATH)));
    String text = mapper.readTree(requests.get(0).getBodyAsString()).path("text").asText();
    assertThat(text).contains("web\\*name").contains("fail \\_at\\_ \\`boot\\`");
  }

  @Test
  void declaresTelegramKindForRouter() {
    assertThat(dispatcher.kind()).isEqualTo(Kind.TELEGRAM);
  }

  private static AlertDestination telegramDestination(String configJson) {
    return new AlertDestination(99L, 1L, Kind.TELEGRAM, "ops-chat", configJson);
  }
}
