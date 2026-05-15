package org.arguslog.api.slack.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;
import org.arguslog.api.slack.domain.SlackWorkspace;

/**
 * Dashboard-facing view of one Slack workspace install. Excludes {@code installToken} on
 * purpose — the bot token is never leaked through any HTTP response.
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
    @JsonProperty("active") boolean active) {

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
        w.isActive());
  }
}
