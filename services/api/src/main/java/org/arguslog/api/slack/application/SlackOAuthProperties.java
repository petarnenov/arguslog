package org.arguslog.api.slack.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Slack OAuth install-flow config. {@code clientId}/{@code clientSecret}/{@code stateSecret}
 * blank → the install controller short-circuits to 503 with "Slack OAuth not configured" so
 * self-hosters who haven't created a Slack app yet aren't surprised by a 500.
 *
 * <p>{@code stateSecret} is intentionally separate from {@code SLACK_SIGNING_SECRET} — leaking
 * one must not let an attacker forge the other. They sit in different env vars in production.
 */
@ConfigurationProperties(prefix = "arguslog.slack.oauth")
public record SlackOAuthProperties(
    String clientId,
    String clientSecret,
    String stateSecret,
    String authorizeUrl,
    String apiBaseUrl,
    String redirectUri,
    String dashboardBaseUrl,
    Duration timeout,
    String scopes) {

  public SlackOAuthProperties {
    if (authorizeUrl == null || authorizeUrl.isBlank()) {
      authorizeUrl = "https://slack.com/oauth/v2/authorize";
    }
    if (apiBaseUrl == null || apiBaseUrl.isBlank()) apiBaseUrl = "https://slack.com/api";
    if (redirectUri == null || redirectUri.isBlank()) {
      redirectUri = "http://localhost:8081/api/v1/slack/oauth/callback";
    }
    if (dashboardBaseUrl == null || dashboardBaseUrl.isBlank()) {
      dashboardBaseUrl = "http://localhost:5173";
    }
    if (timeout == null) timeout = Duration.ofSeconds(5);
    if (scopes == null || scopes.isBlank()) scopes = "commands,chat:write,incoming-webhook";
  }

  public boolean configured() {
    return clientId != null
        && !clientId.isBlank()
        && clientSecret != null
        && !clientSecret.isBlank()
        && stateSecret != null
        && !stateSecret.isBlank();
  }
}
