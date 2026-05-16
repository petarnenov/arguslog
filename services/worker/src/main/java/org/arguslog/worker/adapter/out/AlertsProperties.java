package org.arguslog.worker.adapter.out;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cross-cutting alerts config — single source of truth for values that every dispatcher
 * (Slack, Email, Telegram, Webhook) needs identically. Right now the only such value is
 * {@code dashboardBaseUrl}: the public origin we point at when we render an „Open in
 * Arguslog" deep-link into an issue page. Previously each dispatcher's {@code XxxProperties}
 * owned its own copy and only the email path was actually bound to the
 * {@code ALERTS_DASHBOARD_BASE_URL} env var in {@code application.yml} — the other three
 * silently fell back to their hardcoded localhost default even in production. Lifting the
 * field here keeps the yml binding single-line and the dispatchers in lock-step.
 */
@ConfigurationProperties(prefix = "arguslog.alerts")
public record AlertsProperties(String dashboardBaseUrl) {

  public AlertsProperties {
    if (dashboardBaseUrl == null || dashboardBaseUrl.isBlank()) {
      dashboardBaseUrl = "http://localhost:5173";
    }
  }
}
