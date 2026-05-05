package org.arguslog.worker.application.port;

import org.arguslog.worker.domain.PersistedEvent;

/**
 * Outbound port: hand off a persisted event to the rule-evaluator pipeline. The implementation
 * writes to a separate Redis Stream so dispatch back-pressure (slow Telegram, etc.) can never stall
 * event ingestion.
 */
public interface PersistedEventPublisher {
  void publish(PersistedEvent event);
}
