package org.arguslog.ingest.application.port;

import org.arguslog.ingest.domain.EventEnvelope;

/** Outbound port: publishes a validated event envelope to the events stream. */
public interface EventStreamPublisher {
  void publish(EventEnvelope envelope);
}
