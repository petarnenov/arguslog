package org.arguslog.worker.adapter.out.github;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP-timeout knob for the {@link GithubIssueAlertDispatcher}. The target URL + auth token live
 * per-destination in {@code alert_destinations.config_encrypted} — global config only owns the
 * timeout (5 s default matches the other dispatchers).
 */
@ConfigurationProperties(prefix = "arguslog.alerts.github-issue")
public record GithubIssueProperties(String apiBaseUrl, Duration timeout) {

  public GithubIssueProperties {
    if (apiBaseUrl == null || apiBaseUrl.isBlank()) apiBaseUrl = "https://api.github.com";
    if (timeout == null) timeout = Duration.ofSeconds(10);
  }
}
