package dev.argus.worker.application.port;

import dev.argus.worker.domain.Fingerprint;

/** Pure function: SDK payload (raw JSON) → fingerprint + display fields. Total — never throws. */
public interface Fingerprinter {
  Fingerprint compute(String rawPayload);
}
