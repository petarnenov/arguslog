package org.arguslog.api.releases.application;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.domain.Release;
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

  private final ReleaseRepository repository;

  public ReleaseService(ReleaseRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public Release create(long projectId, String version) {
    String trimmed = requireVersion(version);
    try {
      return repository.create(projectId, trimmed);
    } catch (DuplicateKeyException e) {
      // Race against a concurrent create with the same version — surface as 409 at the controller.
      throw new DuplicateReleaseException(
          "release version already exists for this project: " + trimmed);
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
  public Release update(long projectId, long id, String newVersion) {
    String trimmed = requireVersion(newVersion);
    try {
      return repository
          .updateVersion(projectId, id, trimmed)
          .orElseThrow(
              () ->
                  new ReleaseNotFoundException(
                      "release " + id + " does not exist in project " + projectId));
    } catch (DuplicateKeyException e) {
      throw new DuplicateReleaseException(
          "release version already exists for this project: " + trimmed);
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
}
