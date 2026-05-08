package org.arguslog.api.releases.application;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.releases.domain.Release;

public interface ReleaseUseCase {

  Release create(long projectId, String version);

  List<Release> list(long projectId);

  Optional<Release> get(long projectId, long id);

  /**
   * Renames a release to a new version string. Throws {@link InvalidReleaseException} for
   * empty/long versions, {@link DuplicateReleaseException} if the new version collides, {@link
   * ReleaseNotFoundException} if {@code id} is not found in {@code projectId}.
   */
  Release update(long projectId, long id, String newVersion);

  /**
   * Hard-deletes a release. CASCADE on the FK drops every {@code source_map_artifacts} row, but R2
   * blobs are NOT removed — left for a maintenance script. Returns {@code false} if the release
   * does not exist in the project.
   */
  boolean delete(long projectId, long id);

  /** Thrown when {@code version} is null/blank or longer than the limit. */
  final class InvalidReleaseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidReleaseException(String message) {
      super(message);
    }
  }

  /** Thrown when (projectId, version) already exists. */
  final class DuplicateReleaseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DuplicateReleaseException(String message) {
      super(message);
    }
  }

  /** Thrown when no release with {@code id} exists under {@code projectId}. */
  final class ReleaseNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ReleaseNotFoundException(String message) {
      super(message);
    }
  }
}
