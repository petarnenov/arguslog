package org.arguslog.api.releases.application.port;

import java.util.List;
import org.arguslog.api.releases.domain.SourceMapArtifact;

/**
 * Persistence port for {@code source_map_artifacts}. {@link #upsert} replaces any prior row that
 * shares {@code (release_id, original_path)} so a CLI re-upload after a rebuild is a no-op for
 * downstream readers (latest sha256 wins).
 *
 * <p>Org isolation is enforced by RLS — caller must have pinned {@code arguslog.org_id} via {@code
 * OrgContext} before invoking these methods.
 */
public interface SourceMapArtifactRepository {

  SourceMapArtifact upsert(
      long releaseId, String r2Key, String originalPath, String sha256, long sizeBytes);

  List<SourceMapArtifact> listForRelease(long releaseId);
}
