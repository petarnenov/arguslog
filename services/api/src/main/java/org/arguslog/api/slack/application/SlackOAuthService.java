package org.arguslog.api.slack.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Exchanges a Slack OAuth {@code code} for a bot token via {@code oauth.v2.access}, and builds
 * the authorize-URL the install endpoint redirects browsers to.
 *
 * <p>No SDK — same pattern as {@code ResendInviteEmailSender}. Slack's {@code oauth.v2.access}
 * is a form-urlencoded POST; the response is JSON with {@code ok}, {@code team.id}, {@code
 * team.name}, {@code access_token} (bot token), and {@code authed_user.id}. A {@code "ok":
 * false} body comes back with HTTP 200 carrying an {@code error} string — we surface that as
 * a checked-style {@link Result.Failure} so the controller can render a friendly redirect.
 */
@Service
@ConditionalOnProperty(name = "arguslog.slack.enabled", havingValue = "true", matchIfMissing = true)
public class SlackOAuthService {

  private static final Logger log = LoggerFactory.getLogger(SlackOAuthService.class);

  private final SlackOAuthProperties props;
  private final ObjectMapper mapper;
  private final HttpClient http;

  @Autowired
  public SlackOAuthService(SlackOAuthProperties props, ObjectMapper mapper) {
    this(props, mapper, HttpClient.newBuilder().connectTimeout(props.timeout()).build());
  }

  /** Test ctor — caller supplies a WireMock-backed HttpClient. */
  SlackOAuthService(SlackOAuthProperties props, ObjectMapper mapper, HttpClient http) {
    this.props = props;
    this.mapper = mapper;
    this.http = http;
  }

  /**
   * Builds the URL the install controller 302s the user to. {@code redirectUri} is whatever the
   * controller derived from the request (so dev / staging / prod each get their own callback
   * URL without an env-var per environment).
   */
  public String buildAuthorizeUrl(String state, String redirectUri) {
    return props.authorizeUrl()
        + "?client_id="
        + urlEncode(props.clientId())
        + "&scope="
        + urlEncode(props.scopes())
        + "&redirect_uri="
        + urlEncode(redirectUri)
        + "&state="
        + urlEncode(state);
  }

  /** Exchanges {@code code} for a bot token. */
  public Result exchangeCode(String code, String redirectUri) {
    if (!props.configured()) return Result.failure("not_configured");
    String body =
        "code="
            + urlEncode(code)
            + "&client_id="
            + urlEncode(props.clientId())
            + "&client_secret="
            + urlEncode(props.clientSecret())
            + "&redirect_uri="
            + urlEncode(redirectUri);
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(props.apiBaseUrl() + "/oauth.v2.access"))
            .timeout(props.timeout())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("slack oauth.v2.access transport failure: {}", e.getMessage());
      return Result.failure("transport_error");
    }
    if (resp.statusCode() / 100 != 2) {
      log.warn("slack oauth.v2.access non-2xx: HTTP {} body={}", resp.statusCode(), resp.body());
      return Result.failure("http_" + resp.statusCode());
    }
    JsonNode node;
    try {
      node = mapper.readTree(resp.body());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      log.warn("slack oauth.v2.access unparseable body: {}", e.getMessage());
      return Result.failure("bad_json");
    }
    if (!node.path("ok").asBoolean(false)) {
      String err = node.path("error").asText("unknown");
      log.warn("slack oauth.v2.access ok=false error={}", err);
      return Result.failure(err);
    }
    String teamId = node.path("team").path("id").asText("");
    String teamName = node.path("team").path("name").asText("");
    String accessToken = node.path("access_token").asText("");
    String authedUserId = node.path("authed_user").path("id").asText("");
    if (teamId.isBlank() || accessToken.isBlank()) {
      log.warn("slack oauth.v2.access missing team.id or access_token; body={}", resp.body());
      return Result.failure("incomplete_response");
    }
    // incoming-webhook scope is in our manifest, but Slack only includes the block when the
    // installer ticked through the channel selector. Treat missing/blank as "user declined the
    // channel step" and store nulls — slash commands still work, the dashboard just hides the
    // one-click alert destination button.
    JsonNode hook = node.path("incoming_webhook");
    String hookUrl = blankToNull(hook.path("url").asText(""));
    String hookChannel = blankToNull(hook.path("channel").asText(""));
    return new Result.Success(teamId, teamName, accessToken, authedUserId, hookUrl, hookChannel);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static String urlEncode(String s) {
    return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
  }

  public sealed interface Result {
    static Result failure(String error) {
      return new Failure(error);
    }

    record Success(
        String teamId,
        String teamName,
        String botAccessToken,
        String authedUserId,
        String incomingWebhookUrl,
        String incomingWebhookChannel)
        implements Result {}

    record Failure(String error) implements Result {}
  }
}
