package dev.argus.api.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One row from the events hypertable. {@code payload} is the raw event envelope the SDK posted — we
 * keep it as JsonNode so the API can pass it through to the dashboard verbatim instead of
 * re-shaping (and so PII scrubbing rules continue to be authoritative at write time).
 */
public record Event(UUID id, long issueId, long projectId, Instant receivedAt, JsonNode payload) {}
