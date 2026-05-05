package dev.argus.worker.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One event lifted from the {@code events:incoming} Redis Stream. Mirrors the envelope ingest
 * publishes; the worker is otherwise free to evolve internally.
 */
public record IncomingEvent(
    UUID eventId,
    long projectId,
    String dsnPublicKey,
    Instant receivedAt,
    String rawPayload,
    String clientIp,
    String userAgent) {}
