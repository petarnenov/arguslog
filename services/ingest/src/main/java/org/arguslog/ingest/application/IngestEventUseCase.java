package org.arguslog.ingest.application;

import org.arguslog.ingest.domain.EventEnvelope;

/** Inbound port: ingest a single event from an SDK. */
public interface IngestEventUseCase {

  Result ingest(Command command);

  record Command(
      long projectId, String dsnPublicKey, String rawPayload, String clientIp, String userAgent) {}

  sealed interface Result {
    record Accepted(EventEnvelope envelope) implements Result {}

    record Unauthorized() implements Result {}

    record RateLimited() implements Result {}

    record QuotaExceeded() implements Result {}

    record PayloadTooLarge() implements Result {}
  }
}
