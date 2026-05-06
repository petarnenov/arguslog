package org.arguslog.api.releases.domain;

import java.time.Instant;

/**
 * Persisted record of an uploaded sourcemap. The actual {@code .map} bytes live in R2 at {@code
 * r2_key}; this row is what the worker's symbolication pipeline (P3 #10) joins against when an
 * event arrives carrying a release tag.
 */
public record SourceMapArtifact(
    long id,
    long releaseId,
    String r2Key,
    String originalPath,
    String sha256,
    long sizeBytes,
    Instant createdAt) {}
