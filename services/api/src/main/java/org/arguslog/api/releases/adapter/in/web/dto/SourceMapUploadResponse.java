package org.arguslog.api.releases.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.time.Instant;
import org.arguslog.api.releases.application.SourceMapArtifactUseCase.CreatedUpload;

/**
 * Response to {@code POST /sourcemaps}. Bundles the persisted artifact metadata with a presigned
 * URL the client should PUT the bytes to before {@code expiresAt}.
 */
public record SourceMapUploadResponse(
    SourceMapArtifactResponse artifact,
    @JsonProperty("uploadUrl") URI uploadUrl,
    @JsonProperty("expiresAt") Instant expiresAt) {

  public static SourceMapUploadResponse from(CreatedUpload upload) {
    return new SourceMapUploadResponse(
        SourceMapArtifactResponse.from(upload.artifact()), upload.uploadUrl(), upload.expiresAt());
  }
}
