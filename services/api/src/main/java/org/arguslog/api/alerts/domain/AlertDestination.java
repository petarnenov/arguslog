package org.arguslog.api.alerts.domain;

import java.time.Instant;

/**
 * Public-facing alert destination row. {@code config} is the decrypted JSON the dispatcher needs
 * (chat ids, webhook URLs, …); it never leaves the api process — the controller layer always scrubs
 * it from the response.
 */
public record AlertDestination(
    long id, long orgId, DestinationKind kind, String name, String configJson, Instant createdAt) {}
