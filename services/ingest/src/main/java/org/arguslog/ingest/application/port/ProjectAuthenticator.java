package org.arguslog.ingest.application.port;

import java.util.Optional;

/** Outbound port: validates the SDK-supplied DSN public key and returns the project id if valid. */
public interface ProjectAuthenticator {

  /** Returns the project id when the DSN is authentic and active, otherwise empty. */
  Optional<Long> authenticate(long projectId, String dsnPublicKey);
}
