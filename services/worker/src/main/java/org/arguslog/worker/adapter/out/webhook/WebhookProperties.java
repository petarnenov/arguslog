package org.arguslog.worker.adapter.out.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "arguslog.alerts.webhook")
public record WebhookProperties(String dashboardBaseUrl, Duration timeout) {

  public WebhookProperties {
    if (dashboardBaseUrl == null || dashboardBaseUrl.isBlank()) {
      dashboardBaseUrl = "http://localhost:5173";
    }
    if (timeout == null) timeout = Duration.ofSeconds(5);
  }
}
