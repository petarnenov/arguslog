package org.arguslog.api.releases.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import org.arguslog.api.releases.domain.Release;

public record ReleaseResponse(
    long id,
    @JsonProperty("projectId") long projectId,
    String version,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("releasedAt") Instant releasedAt,
    @JsonProperty("gitSha") String gitSha,
    @JsonProperty("gitRef") String gitRef,
    @JsonProperty("deployStage") String deployStage,
    String changelog) {

  public static ReleaseResponse from(Release r) {
    return new ReleaseResponse(
        r.id(),
        r.projectId(),
        r.version(),
        r.createdAt(),
        r.releasedAt(),
        r.gitSha(),
        r.gitRef(),
        r.deployStage(),
        r.changelog());
  }
}
