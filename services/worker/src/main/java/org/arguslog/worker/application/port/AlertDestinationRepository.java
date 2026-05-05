package org.arguslog.worker.application.port;

import java.util.List;
import org.arguslog.worker.domain.AlertDestination;

/**
 * Loads destinations by id with the encrypted config already decrypted. Returned list preserves
 * input order; ids that don't resolve (deleted / org mismatch) are silently skipped — the
 * dispatcher logs and moves on.
 */
public interface AlertDestinationRepository {

  List<AlertDestination> findAllById(List<Long> ids);
}
