package org.arguslog.api.slack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage for the static body-parsing helper. End-to-end coverage of the interactivity
 * flow (signing → action routing → response_url POST) is exercised through the Spring Boot
 * integration tests by way of the production filter wiring; testing it as a MockMvc slice would
 * require duplicating that filter graph just to mirror what the real request lifecycle already
 * gives us.
 */
class SlackInteractivityControllerTest {

  @Test
  void extractPayloadFieldUrlDecodesTheJsonBlob() {
    String body =
        "payload="
            + java.net.URLEncoder.encode(
                "{\"type\":\"block_actions\"}", java.nio.charset.StandardCharsets.UTF_8);
    assertThat(SlackInteractivityController.extractPayloadField(body))
        .isEqualTo("{\"type\":\"block_actions\"}");
  }

  @Test
  void extractPayloadFieldReturnsNullWhenMissing() {
    assertThat(SlackInteractivityController.extractPayloadField("token=xyz&other=value")).isNull();
  }

  @Test
  void extractPayloadFieldReturnsNullForEmptyBody() {
    assertThat(SlackInteractivityController.extractPayloadField("")).isNull();
    assertThat(SlackInteractivityController.extractPayloadField(null)).isNull();
  }
}
