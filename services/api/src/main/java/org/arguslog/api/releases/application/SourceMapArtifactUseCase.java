package org.arguslog.api.releases.application;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.arguslog.api.releases.domain.SourceMapArtifact;

public interface SourceMapArtifactUseCase {

  /**
   * Persists (or replaces) the artifact metadata and returns it together with a presigned PUT URL
   * the client should upload to immediately. The api never sees the bytes — that round-trip stays
   * between the CLI and R2.
   */
  CreatedUpload create(
      long projectId, long releaseId, String originalPath, String sha256, long sizeBytes);

  List<SourceMapArtifact> list(long projectId, long releaseId);

  /**
   * Drops the artifact row and best-effort removes the underlying R2 blob. Returns {@code false}
   * when the artifact does not exist under the given project + release scope (so the controller
   * can surface a 404 without leaking whether the artifact existed in a different project).
   */
  boolean delete(long projectId, long releaseId, long artifactId);

  record CreatedUpload(SourceMapArtifact artifact, URI uploadUrl, Instant expiresAt) {}

  /** Thrown when payload metadata fails validation (path/sha/size out of bounds). */
  final class InvalidSourceMapException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidSourceMapException(String message) {
      super(message);
    }
  }

  /** Thrown when the parent release does not exist (or belongs to another project). */
  final class ReleaseNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ReleaseNotFoundException(String message) {
      super(message);
    }
  }
}
