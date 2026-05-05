package org.arguslog.api.domain;

import java.time.Instant;

/**
 * Stored DSN row. {@code dsnPublic} is the opaque key clients embed in their SDK config; we never
 * persist a secret half (browser-tier SDKs use public-key-only auth per P1's contract).
 */
public record Dsn(long id, long projectId, String dsnPublic, boolean active, Instant createdAt) {}
