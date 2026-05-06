package org.arguslog.api.application;

import java.util.List;
import java.util.UUID;
import org.arguslog.api.domain.Org;

public interface OrgUseCase {

  Org create(UUID actorId, String actorEmail, String actorDisplayName, String name);

  List<Org> listForUser(UUID userId);

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
}
