package org.arguslog.worker.application;

import java.util.List;
import org.arguslog.worker.domain.AlertRule;
import org.arguslog.worker.domain.PersistedEvent;

/** Drives one persisted event through rule evaluation; returns matched rules for the dispatcher. */
public interface EvaluatePersistedEventUseCase {
  List<AlertRule> evaluate(PersistedEvent event);
}
