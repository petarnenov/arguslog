package org.arguslog.worker.adapter.out.email;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resend HTTP API config. {@code apiKey} blank → email dispatch logs-and-drops at runtime; we
 * intentionally do not crash boot so worker can keep delivering Telegram/Slack/webhook even when
 * email isn't yet provisioned.
 */
@ConfigurationProperties(prefix = "arguslog.alerts.email")
public record EmailProperties(
    String apiBaseUrl, String apiKey, String from, String dashboardBaseUrl, Duration timeout) {

  public EmailProperties {
    if (apiBaseUrl == null || apiBaseUrl.isBlank()) apiBaseUrl = "https://api.resend.com";
    if (from == null || from.isBlank()) from = "alerts@arguslog.local";
    if (dashboardBaseUrl == null || dashboardBaseUrl.isBlank()) {
      dashboardBaseUrl = "http://localhost:5173";
    }
    if (timeout == null) timeout = Duration.ofSeconds(5);
  }

  public boolean configured() {
    return apiKey != null && !apiKey.isBlank();
  }
}
