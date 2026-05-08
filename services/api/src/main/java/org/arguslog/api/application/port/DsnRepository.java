package org.arguslog.api.application.port;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.domain.Dsn;

/** Read-side port for project_keys (DSN rows). project_keys has no RLS — keyed via projectId. */
public interface DsnRepository {

  /** Active rows only, newest first. Revoked keys are filtered out at the SQL layer. */
  List<Dsn> listForProject(long projectId);

  /** Lookup including revoked rows so the use case can distinguish 404 from "already revoked". */
  Optional<Dsn> findByProjectAndId(long projectId, long keyId);
}
