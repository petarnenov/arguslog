package org.arguslog.worker.application.port;

import java.util.Optional;

/**
 * Looks up the {@code source_map_artifacts} row that should be applied to a given {@code
 * (projectId, releaseVersion, originalPath)} triple. Worker-side mirror of api's persistence — we
 * only need the {@code r2_key} to fetch bytes.
 */
public interface SymbolicationRepository {

  Optional<ArtifactRow> findArtifact(long projectId, String releaseVersion, String originalPath);

  record ArtifactRow(long releaseId, String r2Key, String sha256) {}
}
