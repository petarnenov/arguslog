package org.arguslog.api.releases.application;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.domain.Release;
import org.arguslog.api.releases.domain.ReleaseInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReleaseService implements ReleaseUseCase {

  private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

  // Generous but bounded — semver/git-sha/calver all fit. Anything longer is almost certainly
  // a bug (e.g. accidentally piping `git log` instead of `git rev-parse HEAD`).
  static final int MAX_VERSION_LENGTH = 200;

  // Schema-matched bounds. Surfacing them as 400 here is friendlier than a generic 23514 / 22001
  // from Postgres.
  static final int MAX_GIT_SHA_LENGTH = 64;
  static final int MAX_GIT_REF_LENGTH = 255;
  static final int MAX_DEPLOY_STAGE_LENGTH = 64;
  // 64 KiB is enough for a deploy-time changelog snippet without becoming the dumping ground for
  // an entire git log. CLI / API consumers can chunk longer notes into multiple releases.
  static final int MAX_CHANGELOG_LENGTH = 64 * 1024;

  private final ReleaseRepository repository;

  public ReleaseService(ReleaseRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public Release create(long projectId, ReleaseInput input) {
    ReleaseInput sanitized = sanitize(input);
    try {
      return repository.create(projectId, sanitized);
    } catch (DuplicateKeyException e) {
      // Race against a concurrent create with the same version — surface as 409 at the controller.
      throw new DuplicateReleaseException(
          "release version already exists for this project: " + sanitized.version());
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<Release> list(long projectId) {
    return repository.listForProject(projectId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Release> get(long projectId, long id) {
    return repository.find(projectId, id);
  }

  @Override
  @Transactional
  public Release update(long projectId, long id, ReleaseInput input) {
    ReleaseInput sanitized = sanitize(input);
    try {
      return repository
          .update(projectId, id, sanitized)
          .orElseThrow(
              () ->
                  new ReleaseNotFoundException(
                      "release " + id + " does not exist in project " + projectId));
    } catch (DuplicateKeyException e) {
      throw new DuplicateReleaseException(
          "release version already exists for this project: " + sanitized.version());
    }
  }

  @Override
  @Transactional
  public boolean delete(long projectId, long id) {
    boolean removed = repository.delete(projectId, id);
    if (!removed) {
      log.debug("delete no-op: release {} not in project {}", id, projectId);
    }
    return removed;
  }

  /**
   * Trims every string field, rejects an empty version, and enforces length bounds. Returns a new
   * {@link ReleaseInput} with normalized values so the repository layer never sees raw input.
   */
  private static ReleaseInput sanitize(ReleaseInput input) {
    if (input == null) throw new InvalidReleaseException("version is required");
    String version = requireVersion(input.version());
    return new ReleaseInput(
        version,
        input.releasedAt(),
        boundedTrim(input.gitSha(), MAX_GIT_SHA_LENGTH, "gitSha"),
        boundedTrim(input.gitRef(), MAX_GIT_REF_LENGTH, "gitRef"),
        boundedTrim(input.deployStage(), MAX_DEPLOY_STAGE_LENGTH, "deployStage"),
        boundedTrimChangelog(input.changelog()));
  }

  private static String requireVersion(String raw) {
    if (raw == null) throw new InvalidReleaseException("version is required");
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) throw new InvalidReleaseException("version is required");
    if (trimmed.length() > MAX_VERSION_LENGTH) {
      throw new InvalidReleaseException(
          "version must be " + MAX_VERSION_LENGTH + " characters or fewer");
    }
    return trimmed;
  }

  // Bounded trim that normalizes blank → null so the DB stores NULL rather than "".
  private static String boundedTrim(String raw, int max, String fieldName) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) return null;
    if (trimmed.length() > max) {
      throw new InvalidReleaseException(fieldName + " must be " + max + " characters or fewer");
    }
    return trimmed;
  }

  // Changelog gets length-checked but is otherwise preserved verbatim (markdown leading whitespace,
  // trailing newlines on intentional code blocks, etc.). Only "blank → null" normalization runs.
  private static String boundedTrimChangelog(String raw) {
    if (raw == null) return null;
    if (raw.isBlank()) return null;
    if (raw.length() > MAX_CHANGELOG_LENGTH) {
      throw new InvalidReleaseException(
          "changelog must be " + MAX_CHANGELOG_LENGTH + " characters or fewer");
    }
    return raw;
  }
}
