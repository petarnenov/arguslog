package org.arguslog.api.releases.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import org.arguslog.api.releases.domain.SourceMapArtifact;

/** Read-side projection — same fields the worker (P3 #10) will need to fetch the .map. */
public record SourceMapArtifactResponse(
    long id,
    @JsonProperty("releaseId") long releaseId,
    @JsonProperty("r2Key") String r2Key,
    @JsonProperty("originalPath") String originalPath,
    String sha256,
    @JsonProperty("sizeBytes") long sizeBytes,
    @JsonProperty("createdAt") Instant createdAt) {

  public static SourceMapArtifactResponse from(SourceMapArtifact a) {
    return new SourceMapArtifactResponse(
        a.id(),
        a.releaseId(),
        a.r2Key(),
        a.originalPath(),
        a.sha256(),
        a.sizeBytes(),
        a.createdAt());
  }
}
