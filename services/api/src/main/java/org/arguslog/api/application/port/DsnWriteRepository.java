package org.arguslog.api.application.port;

import org.arguslog.api.domain.Dsn;

/**
 * Write-side port for project_keys (DSN rows). Keys are append-only by design — no update / delete
 * here; rotation happens by issuing a new key.
 */
public interface DsnWriteRepository {

  Dsn create(long projectId, String dsnPublic);
}
