package org.arguslog.api.slack.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WireMock-backed exchange test for {@code oauth.v2.access}. Slack's "ok:false" responses come back
 * with HTTP 200 + an {@code error} string in the body, so the {@link
 * SlackOAuthService.Result.Failure} mapping has to be driven by body content, not status alone.
 */
class SlackOAuthServiceTest {

  private WireMockServer wm;
  private SlackOAuthService service;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void start() {
    wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wm.start();
    SlackOAuthProperties props =
        new SlackOAuthProperties(
            "client-abc",
            "secret-xyz",
            "state-sec",
            "https://slack.com/oauth/v2/authorize",
            wm.baseUrl(),
            "http://localhost:8081/api/v1/slack/oauth/callback",
            "http://localhost:5173",
            Duration.ofSeconds(2),
            "commands,chat:write");
    service = new SlackOAuthService(props, mapper, HttpClient.newHttpClient());
  }

  @AfterEach
  void stop() {
    wm.stop();
  }

  @Test
  void successfulExchangeReturnsTeamAndToken() throws Exception {
    wm.stubFor(
        post(urlPathEqualTo("/oauth.v2.access"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"ok": true,
                         "access_token": "xoxb-real-token",
                         "team": {"id":"T123","name":"Acme"},
                         "authed_user": {"id":"U42"}}
                        """)));

    SlackOAuthService.Result r =
        service.exchangeCode("the-code", "http://localhost:8081/api/v1/slack/oauth/callback");

    assertThat(r).isInstanceOf(SlackOAuthService.Result.Success.class);
    var ok = (SlackOAuthService.Result.Success) r;
    assertThat(ok.teamId()).isEqualTo("T123");
    assertThat(ok.teamName()).isEqualTo("Acme");
    assertThat(ok.botAccessToken()).isEqualTo("xoxb-real-token");
    assertThat(ok.authedUserId()).isEqualTo("U42");

    var requests = wm.findAll(postRequestedFor(urlPathEqualTo("/oauth.v2.access")));
    assertThat(requests).hasSize(1);
    String body = requests.get(0).getBodyAsString();
    assertThat(body).contains("code=the-code");
    assertThat(body).contains("client_id=client-abc");
    assertThat(body).contains("client_secret=secret-xyz");
    assertThat(requests.get(0).getHeader("Content-Type"))
        .isEqualTo("application/x-www-form-urlencoded");
  }

  @Test
  void okFalseBodyIsFailureNotException() {
    wm.stubFor(
        post(urlPathEqualTo("/oauth.v2.access"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":false,\"error\":\"invalid_code\"}")));

    SlackOAuthService.Result r = service.exchangeCode("bad-code", "http://localhost:8081/cb");
    assertThat(r).isInstanceOf(SlackOAuthService.Result.Failure.class);
    assertThat(((SlackOAuthService.Result.Failure) r).error()).isEqualTo("invalid_code");
  }

  @Test
  void non2xxStatusIsFailureWithHttpPrefix() {
    wm.stubFor(post(urlPathEqualTo("/oauth.v2.access")).willReturn(aResponse().withStatus(503)));

    SlackOAuthService.Result r = service.exchangeCode("code", "http://localhost:8081/cb");
    assertThat(((SlackOAuthService.Result.Failure) r).error()).isEqualTo("http_503");
  }

  @Test
  void okTrueButMissingTeamIsIncompleteResponse() {
    wm.stubFor(
        post(urlPathEqualTo("/oauth.v2.access"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":true,\"access_token\":\"xoxb-x\"}")));

    SlackOAuthService.Result r = service.exchangeCode("code", "http://localhost:8081/cb");
    assertThat(((SlackOAuthService.Result.Failure) r).error()).isEqualTo("incomplete_response");
  }

  @Test
  void unconfiguredServiceShortCircuitsToFailure() {
    SlackOAuthProperties unset =
        new SlackOAuthProperties(
            "", "", "", null, wm.baseUrl(), null, null, Duration.ofSeconds(2), null);
    SlackOAuthService unconfigured =
        new SlackOAuthService(unset, mapper, HttpClient.newHttpClient());

    SlackOAuthService.Result r = unconfigured.exchangeCode("code", "cb");
    assertThat(((SlackOAuthService.Result.Failure) r).error()).isEqualTo("not_configured");
    // Should NOT have hit WireMock at all — early return for self-hosters without app creds.
    wm.verify(0, postRequestedFor(urlPathEqualTo("/oauth.v2.access")));
  }

  @Test
  void buildAuthorizeUrlEncodesEveryParam() {
    String url = service.buildAuthorizeUrl("the+state value", "http://cb?q=1");
    assertThat(url).startsWith("https://slack.com/oauth/v2/authorize?client_id=client-abc&scope=");
    assertThat(url).contains("state=the%2Bstate+value"); // URLEncoder uses '+' for space
    assertThat(url).contains("redirect_uri=http%3A%2F%2Fcb%3Fq%3D1");
  }

  @Test
  void hitsTheConfiguredApiBase() {
    wm.stubFor(
        post(urlPathEqualTo("/oauth.v2.access"))
            .withHeader("Accept", equalTo("application/json"))
            .willReturn(aResponse().withStatus(200).withBody("{\"ok\":false,\"error\":\"x\"}")));
    service.exchangeCode("c", "cb");
    wm.verify(1, postRequestedFor(urlPathEqualTo("/oauth.v2.access")));
  }
}
