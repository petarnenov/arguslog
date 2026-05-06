package org.arguslog.api.releases.application;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.releases.domain.Release;

public interface ReleaseUseCase {

  Release create(long projectId, String version);

  List<Release> list(long projectId);

  Optional<Release> get(long projectId, long id);

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
}
