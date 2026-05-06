package org.arguslog.worker.application.port;

/**
 * Enriches an event payload with original source locations decoded from the project's uploaded
 * sourcemaps. Implementations MUST never throw and MUST return the input untouched on any failure —
 * symbolication is best-effort enrichment, not a precondition for persistence.
 */
public interface Symbolicator {

  String symbolicate(long projectId, String rawPayload);
}
