package org.arguslog.api.releases.application;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.arguslog.api.application.port.ProjectRepository;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactWriteRepository;
import org.arguslog.api.releases.application.port.SourceMapStorage;
import org.arguslog.api.releases.domain.Release;
import org.arguslog.api.releases.domain.SourceMapArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceMapArtifactService implements SourceMapArtifactUseCase {

  private static final Logger log = LoggerFactory.getLogger(SourceMapArtifactService.class);

  // 50 MB upper bound. Real-world sourcemaps for monolithic SPAs hover around 5–20 MB; 50 leaves
  // headroom while still capping abuse (a malicious key could otherwise PUT GBs into R2).
  static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;
  static final int MAX_PATH_LENGTH = 512;

  private static final Pattern SHA256_HEX = Pattern.compile("^[a-f0-9]{64}$");
  private static final Duration PRESIGN_TTL = Duration.ofMinutes(5);

  private final ReleaseRepository releases;
  private final SourceMapArtifactRepository artifacts;
  private final SourceMapArtifactWriteRepository artifactWrites;
  private final SourceMapStorage storage;
  private final ProjectRepository projects;
  private final Clock clock;

  public SourceMapArtifactService(
      ReleaseRepository releases,
      SourceMapArtifactRepository artifacts,
      SourceMapArtifactWriteRepository artifactWrites,
      SourceMapStorage storage,
      ProjectRepository projects,
      Clock clock) {
    this.releases = releases;
    this.artifacts = artifacts;
    this.artifactWrites = artifactWrites;
    this.storage = storage;
    this.projects = projects;
    this.clock = clock;
  }

  @Override
  @Transactional
  public CreatedUpload create(
      long projectId, long releaseId, String originalPath, String sha256, long sizeBytes) {
    String path = requirePath(originalPath);
    String hash = requireSha256(sha256);
    long size = requireSize(sizeBytes);

    Release release =
        releases
            .find(projectId, releaseId)
            .orElseThrow(
                () ->
                    new ReleaseNotFoundException(
                        "release " + releaseId + " not found in project " + projectId));

    long orgId =
        projects
            .findOrgIdForProject(projectId)
            .orElseThrow(
                () -> new ReleaseNotFoundException("project " + projectId + " no longer exists"));

    String r2Key = buildR2Key(orgId, projectId, release.id(), path);
    SourceMapArtifact stored = artifactWrites.upsert(release.id(), r2Key, path, hash, size);

    URI uploadUrl = storage.presignPut(r2Key, size, PRESIGN_TTL);
    Instant expiresAt = Instant.now(clock).plus(PRESIGN_TTL);
    return new CreatedUpload(stored, uploadUrl, expiresAt);
  }

  @Override
  @Transactional
  public boolean delete(long projectId, long releaseId, long artifactId) {
    // Project/release scope check first — keeps a caller from leaking which artifact ids exist in
    // other projects. Mirrors what {@link #list} does for read scopes.
    Release release =
        releases
            .find(projectId, releaseId)
            .orElseThrow(
                () ->
                    new ReleaseNotFoundException(
                        "release " + releaseId + " not found in project " + projectId));

    // Pull the row before the DELETE so we have its r2_key for the blob removal. If the artifact
    // is missing the controller will translate the false return into a 404.
    SourceMapArtifact existing =
        artifacts.findUnderRelease(release.id(), artifactId).orElse(null);
    if (existing == null) return false;

    boolean removedRow = artifactWrites.delete(release.id(), artifactId);
    if (removedRow) {
      try {
        storage.deleteObject(existing.r2Key());
      } catch (RuntimeException e) {
        // Stranded blob is cheap; api row is the source of truth. The R2 lifecycle policy GCs
        // un-referenced keys on its own schedule.
        log.warn(
            "sourcemap row {} dropped but r2 delete failed for key {}: {}",
            artifactId,
            existing.r2Key(),
            e.getMessage());
      }
    }
    return removedRow;
  }

  @Override
  @Transactional(readOnly = true)
  public List<SourceMapArtifact> list(long projectId, long releaseId) {
    // Walk through Release.find() so org-isolation + project-scope is enforced even if a caller
    // somehow guesses a release id from another project.
    return releases
        .find(projectId, releaseId)
        .map(r -> artifacts.listForRelease(r.id()))
        .orElseThrow(
            () ->
                new ReleaseNotFoundException(
                    "release " + releaseId + " not found in project " + projectId));
  }

  static String buildR2Key(long orgId, long projectId, long releaseId, String originalPath) {
    String trimmed = originalPath.startsWith("/") ? originalPath.substring(1) : originalPath;
    return orgId + "/" + projectId + "/" + releaseId + "/" + trimmed + ".map";
  }

  private static String requirePath(String raw) {
    if (raw == null) throw new InvalidSourceMapException("originalPath is required");
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) throw new InvalidSourceMapException("originalPath is required");
    if (trimmed.length() > MAX_PATH_LENGTH) {
      throw new InvalidSourceMapException(
          "originalPath must be " + MAX_PATH_LENGTH + " characters or fewer");
    }
    if (trimmed.contains("..")) {
      // r2 keys are flat strings, but the worker expects a deterministic mapping back to
      // source paths — banning .. avoids ambiguous "../foo/bar" collisions.
      throw new InvalidSourceMapException("originalPath must not contain '..'");
    }
    return trimmed;
  }

  private static String requireSha256(String raw) {
    if (raw == null || !SHA256_HEX.matcher(raw).matches()) {
      throw new InvalidSourceMapException("sha256 must be 64 lowercase hex characters");
    }
    return raw;
  }

  private static long requireSize(long size) {
    if (size <= 0) throw new InvalidSourceMapException("sizeBytes must be positive");
    if (size > MAX_SIZE_BYTES) {
      throw new InvalidSourceMapException(
          "sizeBytes must be " + MAX_SIZE_BYTES + " or fewer (50 MiB)");
    }
    return size;
  }
}
