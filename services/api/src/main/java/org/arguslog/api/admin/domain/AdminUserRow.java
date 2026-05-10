package org.arguslog.api.admin.domain;

import java.time.Instant;
import java.util.UUID;

/** Row in {@code GET /api/v1/admin/users}. */
public record AdminUserRow(
    UUID userId,
    String email,
    String displayName,
    Instant createdAt,
    int ownedOrgs,
    int memberOrgs,
    String highestPlan) {}
