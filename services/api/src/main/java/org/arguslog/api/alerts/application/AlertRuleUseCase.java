package org.arguslog.api.alerts.application;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.alerts.adapter.in.web.dto.AlertRuleActions;
import org.arguslog.api.alerts.adapter.in.web.dto.AlertRuleConditions;
import org.arguslog.api.alerts.domain.AlertRule;

public interface AlertRuleUseCase {

  AlertRule create(
      long projectId,
      String name,
      AlertRuleConditions conditions,
      AlertRuleActions actions,
      int throttleSeconds,
      boolean enabled);

  List<AlertRule> list(long projectId);

  Optional<AlertRule> get(long projectId, long id);

  Optional<AlertRule> update(
      long projectId,
      long id,
      String name,
      AlertRuleConditions conditions,
      AlertRuleActions actions,
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
