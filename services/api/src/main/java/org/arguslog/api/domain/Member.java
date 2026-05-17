package org.arguslog.api.domain;

import java.time.Instant;
import java.util.UUID;

public record Member(
    UUID userId, String email, String displayName, String role, Instant addedAt, boolean pending) {}
