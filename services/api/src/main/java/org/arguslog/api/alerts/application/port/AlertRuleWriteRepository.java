package org.arguslog.api.alerts.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.arguslog.api.alerts.domain.AlertRule;

/**
 * Write-side port for alert_rules. Kept separate from {@link AlertRuleRepository} so read-only call
 * sites (access guards, listings) don't accidentally pick up mutating capabilities through
 * dependency injection.
 */
public interface AlertRuleWriteRepository {

  AlertRule create(
      long projectId,
      String name,
      JsonNode conditions,
      JsonNode actions,
      int throttleSeconds,
      boolean enabled);

  Optional<AlertRule> update(
      long projectId,
      long id,
      String name,
      JsonNode conditions,
      JsonNode actions,
      int throttleSeconds,
      boolean enabled);

  boolean delete(long projectId, long id);
}
