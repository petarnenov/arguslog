package org.arguslog.api.application;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.domain.Project;

public interface ProjectUseCase {

  Project create(long orgId, String name, String platform);

  List<Project> list(long orgId);

  Optional<Project> get(long orgId, long projectId);

  /** Thrown when create input fails surface-level validation. */
  final class InvalidProjectException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidProjectException(String message) {
      super(message);
    }
  }

  /** Thrown when a project with the derived slug already exists in the same org. */
  final class DuplicateProjectException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DuplicateProjectException(String message) {
      super(message);
    }
  }
}
