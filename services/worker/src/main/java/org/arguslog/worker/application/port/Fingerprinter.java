package org.arguslog.worker.application.port;

import org.arguslog.worker.domain.Fingerprint;

/** Pure function: SDK payload (raw JSON) → fingerprint + display fields. Total — never throws. */
public interface Fingerprinter {
  Fingerprint compute(String rawPayload);
}
