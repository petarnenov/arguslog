package org.arguslog.api.releases.application.port;

import java.util.List;
import org.arguslog.api.releases.domain.SourceMapArtifact;

/**
 * Read-side port for {@code source_map_artifacts}. Org isolation is enforced by RLS — caller must
 * have pinned {@code arguslog.org_id} via {@code OrgContext} before invoking these methods.
 */
public interface SourceMapArtifactRepository {

  List<SourceMapArtifact> listForRelease(long releaseId);
}
