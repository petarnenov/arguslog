package org.arguslog.api.releases.application.port;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.releases.domain.SourceMapArtifact;

/**
 * Read-side port for {@code source_map_artifacts}. Org isolation is enforced by RLS — caller must
 * have pinned {@code arguslog.org_id} via {@code OrgContext} before invoking these methods.
 */
public interface SourceMapArtifactRepository {

  List<SourceMapArtifact> listForRelease(long releaseId);

  /**
   * Looks up a single artifact scoped to its parent release. Returns empty if no row matches —
   * used by the delete path to surface a 404 without leaking which other releases the artifact id
   * might exist under.
   */
  Optional<SourceMapArtifact> findUnderRelease(long releaseId, long artifactId);
}
