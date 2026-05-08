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
}
