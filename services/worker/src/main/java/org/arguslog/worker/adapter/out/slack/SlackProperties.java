package org.arguslog.worker.adapter.out.slack;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Slack only needs a HTTP timeout and a dashboard base url for deep-links — the actual webhook URL
 * is per-destination, stored encrypted in {@code alert_destinations.config_encrypted}.
 */
@ConfigurationProperties(prefix = "arguslog.alerts.slack")
public record SlackProperties(String dashboardBaseUrl, Duration timeout) {

  public SlackProperties {
    if (dashboardBaseUrl == null || dashboardBaseUrl.isBlank()) {
      dashboardBaseUrl = "http://localhost:5173";
    }
    if (timeout == null) timeout = Duration.ofSeconds(5);
  }
}
