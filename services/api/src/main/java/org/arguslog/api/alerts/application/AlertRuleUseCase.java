package org.arguslog.api.alerts.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.alerts.domain.AlertRule;

public interface AlertRuleUseCase {

  AlertRule create(
      long projectId,
      String name,
      JsonNode conditions,
      JsonNode actions,
      int throttleSeconds,
      boolean enabled);

  List<AlertRule> list(long projectId);

  Optional<AlertRule> get(long projectId, long id);

  Optional<AlertRule> update(
      long projectId,
      long id,
      String name,
      JsonNode conditions,
      JsonNode actions,
      int throttleSeconds,
      boolean enabled);

  boolean delete(long projectId, long id);

  /** Thrown when the conditions / actions JSON is malformed enough to be unservable. */
  final class InvalidAlertRuleException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidAlertRuleException(String message) {
      super(message);
    }
  }
}
