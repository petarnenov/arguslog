package org.arguslog.api.application;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.domain.Project;

public interface ProjectUseCase {

  Project create(long orgId, String name, String platform);

  List<Project> list(long orgId);

  Optional<Project> get(long orgId, long projectId);

  /**
   * Soft-archives a project. Caller must be {@code owner} or {@code admin} of the org. Returns
   * {@code false} if the project does not exist or was already archived.
   */
  boolean archive(java.util.UUID actorId, long orgId, long projectId);

  /**
   * Renames a project's display name. Caller must be {@code owner} or {@code admin}. Slug is
   * preserved so DSNs and bookmarks stay valid. Returns the updated project, or empty if it does
   * not exist (or is archived).
   */
  Optional<Project> rename(java.util.UUID actorId, long orgId, long projectId, String name);

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

  /** Thrown when the actor's role is insufficient to mutate a project (archive, etc.). */
  final class ProjectAccessDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ProjectAccessDeniedException(String message) {
      super(message);
    }
  }
}
