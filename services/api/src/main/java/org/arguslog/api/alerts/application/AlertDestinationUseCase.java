package org.arguslog.api.alerts.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.alerts.domain.AlertDestination;
import org.arguslog.api.alerts.domain.DestinationKind;

public interface AlertDestinationUseCase {

  AlertDestination create(long orgId, DestinationKind kind, String name, JsonNode config);

  List<AlertDestination> list(long orgId);

  Optional<AlertDestination> get(long orgId, long id);

  Optional<AlertDestination> update(long orgId, long id, String name, JsonNode config);

  boolean delete(long orgId, long id);

  /**
   * Per-kind config validation. Thrown when the JSON shape doesn't match what the dispatcher will
   * expect at fire time — kept in the use case so the controller can map to 400 problem+json.
   */
  final class InvalidDestinationConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidDestinationConfigException(String message) {
      super(message);
    }
  }
}
