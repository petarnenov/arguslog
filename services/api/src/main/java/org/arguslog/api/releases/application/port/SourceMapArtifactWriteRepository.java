package org.arguslog.api.releases.application.port;

import org.arguslog.api.releases.domain.SourceMapArtifact;

/**
 * Write-side port for {@code source_map_artifacts}. {@link #upsert} replaces any prior row that
 * shares {@code (release_id, original_path)} so a CLI re-upload after a rebuild is a no-op for
 * downstream readers (latest sha256 wins).
 *
 * <p>Org isolation is enforced by RLS — caller must have pinned {@code arguslog.org_id} via {@code
 * OrgContext} before invoking these methods.
 */
public interface SourceMapArtifactWriteRepository {

  SourceMapArtifact upsert(
      long releaseId, String r2Key, String originalPath, String sha256, long sizeBytes);

  /**
   * Drops the artifact row scoped to a single release. Returns {@code true} when a row was deleted,
   * {@code false} when the artifact does not exist under that release. The release-scoping is
   * defensive — controllers already validate the release exists in the project, but this keeps the
   * SQL itself project-aware so an out-of-band caller can't drop a row from another release.
   */
  boolean delete(long releaseId, long artifactId);
}
