package org.arguslog.api.email;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.domain.Org;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Sends org-invitation emails via the Resend HTTP API. Mirrors the worker's alert-email dispatcher
 * (no SDK, plain-text body) so the API stays slim. Synchronous send is acceptable here — invites
 * are very rare compared to alert dispatches, and the user-facing request can absorb a 5s timeout.
 */
@Component
@EnableConfigurationProperties(InviteEmailProperties.class)
public class ResendInviteEmailSender implements InviteEmailSender {

  private static final Logger log = LoggerFactory.getLogger(ResendInviteEmailSender.class);

  private final InviteEmailProperties props;
  private final OrgWriteRepository orgs;
  private final ObjectMapper mapper;
  private final HttpClient http;

  public ResendInviteEmailSender(
      InviteEmailProperties props, OrgWriteRepository orgs, ObjectMapper mapper) {
    this.props = props;
    this.orgs = orgs;
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
    if (!props.configured()) {
      log.warn(
          "arguslog.invites.email.api-key is empty — invite emails will log-and-drop until set");
    }
  }

  @Override
  public void send(String recipientEmail, long orgId) {
    if (!props.configured()) {
      log.warn("resend api key unset; skipping invite email to {}", recipientEmail);
      return;
    }

    String orgName = lookupOrgName(orgId);
    String body;
    try {
      ObjectNode payload = mapper.createObjectNode();
      payload.put("from", props.from());
      payload.putArray("to").add(recipientEmail);
      payload.put("subject", "You've been added to " + orgName + " on Arguslog");
      payload.put("text", renderBody(orgName));
      body = mapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      log.warn("could not encode resend invite payload: {}", e.getMessage());
      return;
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
      if (resp.statusCode() / 100 != 2) {
        log.warn(
            "resend invite to {} failed: HTTP {} body={}",
            recipientEmail,
            resp.statusCode(),
            truncate(resp.body()));
      }
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("resend invite to {} threw: {}", recipientEmail, e.getMessage());
    }
  }

  private String lookupOrgName(long orgId) {
    return orgs.findById(orgId).map(Org::name).orElse("Arguslog org #" + orgId);
  }

  private String renderBody(String orgName) {
    return "You've been added to "
        + orgName
        + " on Arguslog.\n\n"
        + "Sign in to view its projects and recent issues:\n"
        + props.dashboardBaseUrl()
        + "\n\n"
        + "If you don't have an account yet, you'll be prompted to create one with this email "
        + "address — your access to "
        + orgName
        + " links automatically once you log in.\n";
  }

  private static String truncate(String s) {
    if (s == null) return "";
    return s.length() > 200 ? s.substring(0, 200) + "…" : s;
  }
}
