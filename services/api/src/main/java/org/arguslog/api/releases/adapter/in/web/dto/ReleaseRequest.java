package org.arguslog.api.releases.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import org.arguslog.api.releases.domain.ReleaseInput;

/**
 * Wire format for creating / updating a release. {@code version} is required + trimmed; every
 * other field is optional metadata describing the deploy (timestamp, git provenance, stage,
 * changelog notes). Length validation runs in {@link
 * org.arguslog.api.releases.application.ReleaseService}.
 */
public record ReleaseRequest(
    String version,
    @JsonProperty("releasedAt") Instant releasedAt,
    @JsonProperty("gitSha") String gitSha,
    @JsonProperty("gitRef") String gitRef,
    @JsonProperty("deployStage") String deployStage,
    String changelog) {

  public ReleaseInput toInput() {
    return new ReleaseInput(version, releasedAt, gitSha, gitRef, deployStage, changelog);
  }
}
