package org.arguslog.api.application;

import java.util.List;
import org.arguslog.api.domain.Dsn;

public interface DsnUseCase {

  /** Returns the freshly inserted DSN with {@code dsnPublic} populated — caller shows it once. */
  Dsn create(long projectId);

  List<Dsn> list(long projectId);
}
