package org.arguslog.api.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.arguslog.api.application.dto.ProjectStats;
import org.arguslog.api.domain.GitProvider;
import org.arguslog.api.domain.Project;

public interface ProjectUseCase {

  /**
   * Creates a project. {@code gitProvider} + {@code gitRepo} are optional; pass {@code null}
   * for both to skip the Git link, or both non-null to set it. Mixing is a client error.
   */
  Project create(
      long orgId, String name, String platform, GitProvider gitProvider, String gitRepo);

  List<Project> list(long orgId);

  /**
   * Per-project activity snapshot keyed by id — used by the dashboard project-list card. Projects
   * with no events / issues are still returned with zero counts so the caller can render a "no
   * activity yet" state without an extra branch.
   */
  Map<Long, ProjectStats> statsForOrg(long orgId);

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

  /**
   * Sets or clears the project's Git repo reference. Pass both {@code provider} and {@code repo}
   * to link, or both null to clear. Caller must be {@code owner} or {@code admin}. Returns the
   * updated project, or empty if it does not exist (or is archived).
   */
  Optional<Project> updateGitRepo(
      java.util.UUID actorId, long orgId, long projectId, GitProvider provider, String repo);

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
