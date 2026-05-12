package org.arguslog.api.admin.domain;

import java.time.Instant;
import java.util.UUID;

/** Row in {@code GET /api/v1/admin/orgs}. */
public record AdminOrgRow(
    long orgId,
    String slug,
    String name,
    String tier,
    Instant createdAt,
    UUID ownerId,
    String ownerEmail,
    int projects,
    int members,
    long events30d,
    Instant tierExpiresAt,
    String tierReason,
    String tierGrantedByEmail) {}
