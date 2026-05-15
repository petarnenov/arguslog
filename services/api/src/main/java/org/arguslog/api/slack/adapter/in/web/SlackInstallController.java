package org.arguslog.api.slack.adapter.in.web;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.arguslog.api.security.AuthActor;
import org.arguslog.api.slack.application.SlackInstallStateCodec;
import org.arguslog.api.slack.application.SlackOAuthProperties;
import org.arguslog.api.slack.application.SlackOAuthService;
import org.arguslog.api.slack.application.port.SlackWorkspaceWriteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slack OAuth install flow. Two endpoints:
 *
 * <ol>
 *   <li>{@code GET /api/v1/orgs/{orgId}/integrations/slack/oauth/install} — JWT-protected.
 *       Returns {@code 200 {authorizeUrl}} as JSON so the dashboard can navigate the browser
 *       to Slack itself. We can't 302 directly because the dashboard and api live on different
 *       origins (app.arguslog.org vs api.arguslog.org) — a top-level browser navigation to
 *       this endpoint carries no {@code Authorization: Bearer} header and hits 401 before the
 *       controller can build the redirect.
 *   <li>{@code GET /api/v1/slack/oauth/callback} — allow-listed under {@code /api/v1/slack/**}
 *       in SecurityConfig. The signed state IS the authentication; it carries the orgId +
 *       userId from the install step, so Slack callbacks for a different org can't sneak
 *       through.
 * </ol>
 *
 * <p>Both endpoints short-circuit to 503 when {@link SlackOAuthProperties#configured()} is
 * false (self-hoster hasn't created a Slack app yet) instead of forwarding a half-broken
 * request to Slack and confusing the user with a generic 4xx from Slack.
 */
@RestController
@ConditionalOnProperty(name = "arguslog.slack.enabled", havingValue = "true", matchIfMissing = true)
public class SlackInstallController {

  private static final Logger log = LoggerFactory.getLogger(SlackInstallController.class);

  private final SlackOAuthProperties props;
  private final SlackInstallStateCodec stateCodec;
  private final SlackOAuthService oauth;
  private final SlackWorkspaceWriteRepository workspaces;

  public SlackInstallController(
      SlackOAuthProperties props,
      SlackInstallStateCodec stateCodec,
      SlackOAuthService oauth,
      SlackWorkspaceWriteRepository workspaces) {
    this.props = props;
    this.stateCodec = stateCodec;
    this.oauth = oauth;
    this.workspaces = workspaces;
  }

  public record InstallResponse(String authorizeUrl) {}

  @GetMapping(
      value = "/api/v1/orgs/{orgId}/integrations/slack/oauth/install",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> install(@PathVariable long orgId) {
    if (!props.configured()) return notConfigured();
    UUID userId = AuthActor.currentUserId();
    String state = stateCodec.encode(orgId, userId);
    String authorizeUrl = oauth.buildAuthorizeUrl(state, props.redirectUri());
    return ResponseEntity.ok(new InstallResponse(authorizeUrl));
  }

  @GetMapping(value = "/api/v1/slack/oauth/callback", produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> callback(
      @RequestParam(value = "code", required = false) String code,
      @RequestParam(value = "state", required = false) String state,
      @RequestParam(value = "error", required = false) String slackError) {
    if (!props.configured()) return notConfigured();

    // Slack reports user-cancelled / error scenarios via ?error=... — surface that as a
    // friendly redirect with a query flag, not an opaque 4xx.
    if (slackError != null && !slackError.isBlank()) {
      log.info("slack oauth callback returned error={}", slackError);
      return redirectToDashboard("error=" + slackError);
    }
    if (code == null || code.isBlank() || state == null || state.isBlank()) {
      return ResponseEntity.badRequest().body("missing code or state");
    }

    SlackInstallStateCodec.Result decoded = stateCodec.decode(state);
    if (!(decoded instanceof SlackInstallStateCodec.Result.Ok ok)) {
      var reason = ((SlackInstallStateCodec.Result.Invalid) decoded).reason();
      log.warn("slack oauth callback rejected: state {}", reason);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("bad state");
    }

    SlackOAuthService.Result exchanged = oauth.exchangeCode(code, props.redirectUri());
    if (!(exchanged instanceof SlackOAuthService.Result.Success success)) {
      var err = ((SlackOAuthService.Result.Failure) exchanged).error();
      log.warn("slack token exchange failed for org={}: {}", ok.orgId(), err);
      return redirectToDashboard("error=token_exchange_" + err);
    }

    workspaces.upsert(
        success.teamId(),
        success.teamName().isBlank() ? success.teamId() : success.teamName(),
        success.botAccessToken(),
        ok.orgId(),
        null, // default project picked from the dashboard after install
        ok.userId());
    log.info(
        "slack workspace installed: team={} ({}), org={}, by user={}",
        success.teamName(),
        success.teamId(),
        ok.orgId(),
        ok.userId());

    return redirectToDashboard("installed=" + urlEncode(success.teamName()));
  }

  private ResponseEntity<String> notConfigured() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body("Slack OAuth is not configured on this Arguslog instance.");
  }

  private ResponseEntity<String> redirectToDashboard(String query) {
    String url = props.dashboardBaseUrl() + "/settings/integrations/slack?" + query;
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
  }

  private static String urlEncode(String s) {
    return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
  }
}
