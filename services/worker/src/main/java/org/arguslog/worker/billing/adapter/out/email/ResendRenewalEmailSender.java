package org.arguslog.worker.billing.adapter.out.email;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import org.arguslog.worker.adapter.out.email.EmailProperties;
import org.arguslog.worker.billing.application.port.RenewalEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resend HTTP send for renewal-reminder emails. Re-uses the alert {@code EmailProperties} (same
 * Resend API key, dashboard base URL) so a single env var configures both surfaces. From-address is
 * hard-coded to {@code billing@<sender-domain>} derived from the alert sender — keeps reply routing
 * predictable without adding more config knobs.
 */
@Component
public class ResendRenewalEmailSender implements RenewalEmailSender {

  private static final Logger log = LoggerFactory.getLogger(ResendRenewalEmailSender.class);

  private final EmailProperties props;
  private final ObjectMapper mapper;
  private final HttpClient http;

  public ResendRenewalEmailSender(EmailProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
  }

  @Override
  public boolean send(
      String recipientEmail, String orgName, String orgSlug, LocalDate expiresAt, int daysAhead) {
    if (!props.configured()) {
      log.warn(
          "resend api key unset; skipping renewal reminder to {} (org={}, days={})",
          recipientEmail,
          orgName,
          daysAhead);
      return false;
    }

    String body;
    try {
      ObjectNode payload = mapper.createObjectNode();
      payload.put("from", deriveFromAddress(props.from()));
      payload.putArray("to").add(recipientEmail);
      payload.put("subject", subject(orgName, daysAhead));
      payload.put("text", textBody(orgName, orgSlug, expiresAt, daysAhead));
      body = mapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      log.warn("renewal reminder payload encode failed: {}", e.getMessage());
      return false;
    }

    HttpRequest req =
        HttpRequest.newBuilder(URI.create(props.apiBaseUrl() + "/emails"))
            .timeout(props.timeout())
            .header("Authorization", "Bearer " + props.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 == 2) return true;
      log.warn(
          "renewal reminder to {} failed: HTTP {} body={}",
          recipientEmail,
          resp.statusCode(),
          truncate(resp.body()));
      return false;
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("renewal reminder to {} threw: {}", recipientEmail, e.getMessage());
      return false;
    }
  }

  private static String subject(String orgName, int daysAhead) {
    return switch (daysAhead) {
      case 1 -> "Your " + orgName + " Pro plan expires tomorrow";
      case 7 -> "Your " + orgName + " Pro plan expires in 7 days";
      default -> "Your " + orgName + " Pro plan expires in " + daysAhead + " days";
    };
  }

  private String textBody(String orgName, String orgSlug, LocalDate expiresAt, int daysAhead) {
    return "Heads up — your "
        + orgName
        + " Arguslog Pro plan expires on "
        + expiresAt
        + " ("
        + daysAhead
        + " day"
        + (daysAhead == 1 ? "" : "s")
        + " from now).\n\n"
        + "Renew with crypto in a few clicks:\n"
        + props.dashboardBaseUrl()
        + "/orgs/"
        + orgSlug
        + "/billing\n\n"
        + "If you take no action, your plan will downgrade to Free after a 7-day grace window. "
        + "Existing data is preserved; quotas and retention drop to the Free tier.\n";
  }

  private static String deriveFromAddress(String alertFrom) {
    int at = alertFrom.indexOf('@');
    if (at < 0) return alertFrom;
    return "billing" + alertFrom.substring(at);
  }

  private static String truncate(String s) {
    if (s == null) return "";
    return s.length() > 200 ? s.substring(0, 200) + "…" : s;
  }
}
