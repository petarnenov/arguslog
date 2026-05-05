package org.arguslog.worker.adapter.out.telegram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Telegram bot config. {@code apiBaseUrl} is overridable so tests can point at WireMock.
 *
 * @param botToken empty in dev means "telegram dispatch is a no-op" — we log a warn at boot instead
 *     of crashing the worker.
 * @param dashboardBaseUrl shown in messages so an on-call can click straight to the issue.
 */
@ConfigurationProperties(prefix = "argus.alerts.telegram")
public record TelegramProperties(
    String apiBaseUrl, String botToken, String dashboardBaseUrl, Duration timeout) {

  public TelegramProperties {
    if (apiBaseUrl == null || apiBaseUrl.isBlank()) apiBaseUrl = "https://api.telegram.org";
    if (dashboardBaseUrl == null || dashboardBaseUrl.isBlank()) {
      dashboardBaseUrl = "http://localhost:5173";
    }
    if (timeout == null) timeout = Duration.ofSeconds(5);
  }

  public boolean configured() {
    return botToken != null && !botToken.isBlank();
  }
}
