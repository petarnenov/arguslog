package org.arguslog.worker.adapter.out.slack;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Slack only needs a HTTP timeout — the actual webhook URL is per-destination, stored encrypted
 * in {@code alert_destinations.config_encrypted}. The dashboard base url that the "Open in
 * Arguslog" deep-link uses comes from the shared {@link
 * org.arguslog.worker.adapter.out.AlertsProperties}.
 */
@ConfigurationProperties(prefix = "arguslog.alerts.slack")
public record SlackProperties(Duration timeout) {

  public SlackProperties {
    if (timeout == null) timeout = Duration.ofSeconds(5);
  }
}
