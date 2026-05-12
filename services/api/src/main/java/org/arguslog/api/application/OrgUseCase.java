package org.arguslog.api.application;

import java.util.List;
import java.util.UUID;
import org.arguslog.api.domain.Org;

public interface OrgUseCase {

  Org create(UUID actorId, String name);

  List<Org> listForUser(UUID userId);

  /**
   * Renames an org's display name. Caller must be {@code owner}. Slug is intentionally NOT changed —
   * URLs/DSNs/bookmarks remain stable. Returns the updated org, or empty if the org does not exist.
   */
  java.util.Optional<Org> rename(UUID actorId, long orgId, String name);

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

  /**
   * Thrown when the caller has already reached the per-plan owner-org cap. Maps to a 402 Payment
   * Required problem at the controller — the customer needs to upgrade an existing org (or get
   * invited to someone else's) to create another.
   */
  final class OrgQuotaExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OrgQuotaExceededException(String message) {
      super(message);
    }
  }
}
