package org.arguslog.api.application.port;

import java.util.Optional;
import org.arguslog.api.domain.Dsn;

/**
 * Write-side port for project_keys (DSN rows). Keys are append-on-create and revoke-only — physical
 * deletes never happen so the audit trail of which DSN ingested which event stays intact even after
 * a user revokes a key.
 */
public interface DsnWriteRepository {

  Dsn create(long projectId, String dsnPublic);

  /**
   * Soft-revoke: flips {@code active} from true to false. Returns the updated row when the UPDATE
   * matched an active key, empty otherwise (already revoked or missing).
   */
  Optional<Dsn> deactivate(long keyId);
}
