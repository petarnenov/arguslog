package org.arguslog.api.application;

import java.util.List;
import java.util.UUID;
import org.arguslog.api.domain.Org;

public interface OrgUseCase {

  Org create(UUID actorId, String actorEmail, String actorDisplayName, String name);

  List<Org> listForUser(UUID userId);

  /**
   * Hard-deletes an org. Caller must be {@code owner}. Returns {@code false} if the org does not
   * exist (after the membership check, so non-members are still rejected with 404 by the access
   * guard).
   */
  boolean delete(UUID actorId, long orgId);

  /** Thrown when create input fails surface-level validation. */
  final class InvalidOrgException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidOrgException(String message) {
      super(message);
    }
  }

  /** Thrown when an org with the derived slug already exists (per-instance unique). */
  final class DuplicateOrgException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DuplicateOrgException(String message) {
      super(message);
    }
  }

  /** Thrown when the actor's role is insufficient to delete an org. */
  final class OrgAccessDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OrgAccessDeniedException(String message) {
      super(message);
    }
  }
}
