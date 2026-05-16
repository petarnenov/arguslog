package org.arguslog.api.alerts.application.port;

import java.util.Optional;
import org.arguslog.api.alerts.domain.AlertDestination;
import org.arguslog.api.alerts.domain.DestinationKind;

/**
 * Write-side port for {@code alert_destinations}. {@code configJson} crosses this port in
 * plaintext; the JDBC adapter is responsible for the encrypt-on-write / decrypt-on-read dance via
 * {@link SecretCipher}.
 */
public interface AlertDestinationWriteRepository {

  AlertDestination create(long orgId, DestinationKind kind, String name, String configJson);

  /** Returns the updated row, or empty if it didn't exist under this org. */
  Optional<AlertDestination> update(long orgId, long id, String name, String configJson);

  /**
   * Flips just the {@code enabled} column. Returns the refreshed row or empty when the
   * destination doesn't exist under this org.
   */
  Optional<AlertDestination> setEnabled(long orgId, long id, boolean enabled);

  /** Returns true if a row was actually deleted. */
  boolean delete(long orgId, long id);
}
