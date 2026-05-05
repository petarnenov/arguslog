package org.arguslog.worker.domain;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Worker-side mirror of {@code alert_rules}. Lighter than the api's record — the dispatcher only
 * needs id, conditions, actions and the throttle. Persisted authoritative copy lives behind the
 * api; this record is read-only from the worker.
 */
public record AlertRule(
    long id, long projectId, JsonNode conditions, JsonNode actions, int throttleSeconds) {}
