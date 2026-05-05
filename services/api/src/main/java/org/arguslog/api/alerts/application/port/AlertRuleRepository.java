package org.arguslog.api.alerts.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.alerts.domain.AlertRule;

public interface AlertRuleRepository {

  AlertRule create(
      long projectId,
      String name,
      JsonNode conditions,
      JsonNode actions,
      int throttleSeconds,
      boolean enabled);

  List<AlertRule> listForProject(long projectId);

  Optional<AlertRule> find(long projectId, long id);

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
