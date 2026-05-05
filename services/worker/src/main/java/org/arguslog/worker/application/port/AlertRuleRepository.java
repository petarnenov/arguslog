package org.arguslog.worker.application.port;

import java.util.List;
import org.arguslog.worker.domain.AlertRule;

/** Read-only access to enabled alert rules for a project. Fronted by Caffeine in P4. */
public interface AlertRuleRepository {
  List<AlertRule> enabledForProject(long projectId);
}
