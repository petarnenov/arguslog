package org.arguslog.worker.application;

import org.arguslog.worker.domain.AlertRule;
import org.arguslog.worker.domain.PersistedEvent;

/**
 * Inbound port: turn a (rule, event) match into outgoing messages on every destination the rule
 * fans out to. Returns the count of destinations actually attempted (post-resolution); throttling
 * is layered above in P3 #5.
 */
public interface DispatchAlertUseCase {

  int dispatch(AlertRule rule, PersistedEvent event);
}
