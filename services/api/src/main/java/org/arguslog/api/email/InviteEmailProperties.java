package org.arguslog.api.email;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resend HTTP API config for invitation emails. {@code apiKey} blank → invite emails log-and-drop
 * at runtime; the membership row is still inserted so the invitee can log in and see the org.
 */
@ConfigurationProperties(prefix = "arguslog.invites.email")
public record InviteEmailProperties(
    String apiBaseUrl, String apiKey, String from, String dashboardBaseUrl, Duration timeout) {

  public InviteEmailProperties {
    if (apiBaseUrl == null || apiBaseUrl.isBlank()) apiBaseUrl = "https://api.resend.com";
    if (from == null || from.isBlank()) from = "noreply@arguslog.local";
    if (dashboardBaseUrl == null || dashboardBaseUrl.isBlank()) {
      dashboardBaseUrl = "http://localhost:5173";
    }
    if (timeout == null) timeout = Duration.ofSeconds(5);
  }

  public boolean configured() {
    return apiKey != null && !apiKey.isBlank();
  }
}
