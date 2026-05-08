package org.arguslog.api.application;

import java.util.List;
import org.arguslog.api.domain.Dsn;

public interface DsnUseCase {

  /** Returns the freshly inserted DSN with {@code dsnPublic} populated — caller shows it once. */
  Dsn create(long projectId);

  /** Returns active DSNs for the project, newest first. Revoked rows are excluded. */
  List<Dsn> list(long projectId);

  /**
   * Marks the key as revoked (sets {@code active=false}). Idempotency is intentionally NOT
   * provided: re-revoking a revoked key throws {@link DsnAlreadyRevokedException} so a UI
   * showing a stale list surfaces an explicit error rather than silently doing nothing.
   *
   * @throws DsnNotFoundException if no key with that id exists under the given project
   * @throws DsnAlreadyRevokedException if the key is already revoked
   */
  Dsn revoke(long projectId, long keyId);

  final class DsnNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DsnNotFoundException(long projectId, long keyId) {
      super("DSN " + keyId + " not found under project " + projectId);
    }
  }

  final class DsnAlreadyRevokedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DsnAlreadyRevokedException(long keyId) {
      super("DSN " + keyId + " is already revoked");
    }
  }
}
