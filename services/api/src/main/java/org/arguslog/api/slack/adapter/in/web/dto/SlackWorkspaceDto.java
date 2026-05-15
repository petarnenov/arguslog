package org.arguslog.api.slack.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;
import org.arguslog.api.slack.domain.SlackWorkspace;

/**
 * Dashboard-facing view of one Slack workspace install. Excludes {@code installToken} and
 * {@code webhookUrl} on purpose — bot token + webhook URL are both bearer-style secrets that
 * never leave the api process. The dashboard only needs to know <em>that</em> a webhook is
 * available (so the "Create alert destination" button can render) and which channel it lands
 * in (so the user knows where alerts will appear).
 */
public record SlackWorkspaceDto(
    @JsonProperty("id") long id,
    @JsonProperty("slackTeamId") String slackTeamId,
    @JsonProperty("slackTeamName") String slackTeamName,
    @JsonProperty("orgId") long orgId,
    @JsonProperty("defaultProjectId") Long defaultProjectId,
    @JsonProperty("installedByUserId") UUID installedByUserId,
    @JsonProperty("installedAt") Instant installedAt,
    @JsonProperty("deactivatedAt") Instant deactivatedAt,
    @JsonProperty("active") boolean active,
    @JsonProperty("webhookChannel") String webhookChannel,
    @JsonProperty("hasWebhook") boolean hasWebhook) {

  public static SlackWorkspaceDto from(SlackWorkspace w) {
    return new SlackWorkspaceDto(
        w.id(),
        w.slackTeamId(),
        w.slackTeamName(),
        w.orgId(),
        w.defaultProjectId(),
        w.installedByUserId(),
        w.installedAt(),
        w.deactivatedAt(),
        w.isActive(),
        w.webhookChannel(),
        w.hasWebhook());
  }
}
