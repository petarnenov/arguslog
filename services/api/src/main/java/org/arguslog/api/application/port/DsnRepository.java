package org.arguslog.api.application.port;

import java.util.List;
import org.arguslog.api.domain.Dsn;

/** Read-side port for project_keys (DSN rows). project_keys has no RLS — keyed via projectId. */
public interface DsnRepository {

  List<Dsn> listForProject(long projectId);
}
