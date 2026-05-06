package org.arguslog.api.releases.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import org.arguslog.api.releases.domain.Release;

public record ReleaseResponse(
    long id,
    @JsonProperty("projectId") long projectId,
    String version,
    @JsonProperty("createdAt") Instant createdAt) {

  public static ReleaseResponse from(Release r) {
    return new ReleaseResponse(r.id(), r.projectId(), r.version(), r.createdAt());
  }
}
