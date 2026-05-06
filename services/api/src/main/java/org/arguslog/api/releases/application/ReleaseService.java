package org.arguslog.api.releases.application;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.domain.Release;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReleaseService implements ReleaseUseCase {

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
