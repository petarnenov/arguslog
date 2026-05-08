package org.arguslog.api.alerts.application.port;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.alerts.domain.AlertRule;

/** Read-side port for alert_rules. */
public interface AlertRuleRepository {

  List<AlertRule> listForProject(long projectId);

  Optional<AlertRule> find(long projectId, long id);
}
