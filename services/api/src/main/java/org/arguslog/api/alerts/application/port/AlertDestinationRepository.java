package org.arguslog.api.alerts.application.port;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.alerts.domain.AlertDestination;

/** Read-side port for {@code alert_destinations}. */
public interface AlertDestinationRepository {

  List<AlertDestination> listForOrg(long orgId);

  Optional<AlertDestination> find(long orgId, long id);
}
