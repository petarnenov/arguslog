package org.arguslog.worker.application;

import org.arguslog.worker.domain.IncomingEvent;

/**
 * Drives one event through the worker pipeline. Throws if processing fails — caller decides ack.
 */
public interface ProcessEventUseCase {
  Result process(IncomingEvent event);

  sealed interface Result {
    /** Persisted; safe to ACK the stream message. */
    record Persisted(long issueId, boolean newIssue) implements Result {}

    /** Payload was unparseable; stored under "unknown" fingerprint so it isn't lost. */
    record SalvagedAsUnknown(long issueId) implements Result {}

    /** Transient failure — caller should NOT ack so Redis redelivers. */
    record Retryable(String reason) implements Result {}
  }
}
