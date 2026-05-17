package org.arguslog.api.slack.adapter.in.web;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.domain.Org;
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
 *   <li>{@code GET /api/v1/orgs/{orgId}/integrations/slack/oauth/install} — JWT-protected. Returns
 *       {@code 200 {authorizeUrl}} as JSON so the dashboard can navigate the browser to Slack
 *       itself. We can't 302 directly because the dashboard and api live on different origins
 *       (app.arguslog.org vs api.arguslog.org) — a top-level browser navigation to this endpoint
 *       carries no {@code Authorization: Bearer} header and hits 401 before the controller can
 *       build the redirect.
 *   <li>{@code GET /api/v1/slack/oauth/callback} — allow-listed under {@code /api/v1/slack/**} in
 *       SecurityConfig. The signed state IS the authentication; it carries the orgId + userId from
 *       the install step, so Slack callbacks for a different org can't sneak through.
 * </ol>
 *
 * <p>Both endpoints short-circuit to 503 when {@link SlackOAuthProperties#configured()} is false
 * (self-hoster hasn't created a Slack app yet) instead of forwarding a half-broken request to Slack
 * and confusing the user with a generic 4xx from Slack.
 */
@RestController
@ConditionalOnProperty(name = "arguslog.slack.enabled", havingValue = "true", matchIfMissing = true)
public class SlackInstallController {

  private static final Logger log = LoggerFactory.getLogger(SlackInstallController.class);

  private final SlackOAuthProperties props;
  private final SlackInstallStateCodec stateCodec;
  private final SlackOAuthService oauth;
  private final SlackWorkspaceWriteRepository workspaces;
  private final OrgWriteRepository orgs;

  public SlackInstallController(
      SlackOAuthProperties props,
      SlackInstallStateCodec stateCodec,
      SlackOAuthService oauth,
      SlackWorkspaceWriteRepository workspaces,
      OrgWriteRepository orgs) {
    this.props = props;
    this.stateCodec = stateCodec;
    this.oauth = oauth;
    this.workspaces = workspaces;
    this.orgs = orgs;
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
    // friendly redirect with a query flag, not an opaque 4xx. Slack still echoes state on
    // error, so we can usually decode it to land on the right org's integrations page.
    if (slackError != null && !slackError.isBlank()) {
      log.info("slack oauth callback returned error={}", slackError);
      Long orgIdForRedirect = null;
      if (state != null
          && !state.isBlank()
          && stateCodec.decode(state) instanceof SlackInstallStateCodec.Result.Ok ok) {
        orgIdForRedirect = ok.orgId();
      }
      return redirectToDashboard(orgIdForRedirect, "error=" + slackError);
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
      return redirectToDashboard(ok.orgId(), "error=token_exchange_" + err);
    }

    workspaces.upsert(
        success.teamId(),
        success.teamName().isBlank() ? success.teamId() : success.teamName(),
        success.botAccessToken(),
        ok.orgId(),
        null, // default project picked from the dashboard after install
        ok.userId(),
        success.incomingWebhookUrl(),
        success.incomingWebhookChannel());
    log.info(
        "slack workspace installed: team={} ({}), org={}, by user={}",
        success.teamName(),
        success.teamId(),
        ok.orgId(),
        ok.userId());

    return redirectToDashboard(ok.orgId(), "installed=" + urlEncode(success.teamName()));
  }

  private ResponseEntity<String> notConfigured() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body("Slack OAuth is not configured on this Arguslog instance.");
  }

  /**
   * Where to drop the user after the callback. The integrations page is org-scoped ({@code
   * /orgs/<slug>/integrations/slack}) so we need the slug — look it up from the orgId that the
   * state token carried. If we don't have an orgId (e.g. Slack returned an error with a missing/bad
   * state) or the org row vanished mid-flow, fall back to the dashboard root so the user at least
   * lands somewhere that exists rather than a 404.
   */
  private ResponseEntity<String> redirectToDashboard(Long orgId, String query) {
    Optional<Org> org = orgId == null ? Optional.empty() : orgs.findById(orgId);
    String path =
        org.map(o -> "/orgs/" + o.slug() + "/integrations/slack?" + query).orElse("/?" + query);
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(props.dashboardBaseUrl() + path))
        .build();
  }

  private static String urlEncode(String s) {
    return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
  }
}
