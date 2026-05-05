package org.arguslog.api.alerts.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * One row from {@code alert_rules}. {@code conditions} carries the JSON DSL the worker's rule
 * evaluator interprets (level / tag / firstSeenWindow / occurrenceThreshold — all AND-ed). {@code
 * actions} carries the list of destination ids to fan out to: {@code {"destinationIds":[1,2,3]}}.
 *
 * <p>Both blobs are intentionally JsonNode at this layer — the api stores + returns them as-is and
 * the worker is the source of truth for what they mean. Schema migrations on the DSL go alongside
 * worker changes, never API-only.
 */
public record AlertRule(
    long id,
    long projectId,
    String name,
    JsonNode conditions,
    JsonNode actions,
    int throttleSeconds,
    boolean enabled,
    Instant createdAt) {}
