package org.arguslog.worker.adapter.out.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Webhook dispatcher needs only a HTTP timeout — the destination URL is per-destination, stored
 * encrypted in {@code alert_destinations.config_encrypted}. The dashboard base url that the
 * outgoing payload embeds for the "Open in Arguslog" deep-link comes from the shared
 * {@link org.arguslog.worker.adapter.out.AlertsProperties}.
 */
@ConfigurationProperties(prefix = "arguslog.alerts.webhook")
public record WebhookProperties(Duration timeout) {

  public WebhookProperties {
    if (timeout == null) timeout = Duration.ofSeconds(5);
  }
}
