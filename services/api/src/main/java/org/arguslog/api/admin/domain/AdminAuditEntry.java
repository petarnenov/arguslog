package org.arguslog.api.admin.domain;

import java.time.Instant;
import java.util.UUID;

public record AdminAuditEntry(
    long id,
    Instant ts,
    UUID adminUser,
    String adminEmail,
    String action,
    String targetType,
    String targetId,
    String payloadJson) {}
