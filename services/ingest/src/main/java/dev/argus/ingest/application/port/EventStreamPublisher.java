package dev.argus.ingest.application.port;

import dev.argus.ingest.domain.EventEnvelope;

/** Outbound port: publishes a validated event envelope to the events stream. */
public interface EventStreamPublisher {
  void publish(EventEnvelope envelope);
}
