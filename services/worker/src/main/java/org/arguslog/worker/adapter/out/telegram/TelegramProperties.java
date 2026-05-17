package org.arguslog.worker.adapter.out.telegram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Telegram bot config. {@code apiBaseUrl} is overridable so tests can point at WireMock.
 *
 * @param botToken empty in dev means "telegram dispatch is a no-op" — we log a warn at boot instead
 *     of crashing the worker.
 *     <p>The dashboard base url for the "Open in Arguslog" deep-link lives in the shared {@link
 *     org.arguslog.worker.adapter.out.AlertsProperties}, not here.
 */
@ConfigurationProperties(prefix = "arguslog.alerts.telegram")
public record TelegramProperties(String apiBaseUrl, String botToken, Duration timeout) {

  public TelegramProperties {
    if (apiBaseUrl == null || apiBaseUrl.isBlank()) apiBaseUrl = "https://api.telegram.org";
    if (timeout == null) timeout = Duration.ofSeconds(5);
  }

  public boolean configured() {
    return botToken != null && !botToken.isBlank();
  }
}
