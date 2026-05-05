package dev.argus.ingest.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The immutable, validated event we forward to the worker via Redis Streams. The raw SDK payload is
 * stored verbatim in {@code rawPayload} so the worker can apply server-side scrubbing before
 * persistence.
 */
public record EventEnvelope(
    UUID eventId,
    long projectId,
    String dsnPublicKey,
    Instant receivedAt,
    String rawPayload,
    String clientIp,
    String userAgent) {}
