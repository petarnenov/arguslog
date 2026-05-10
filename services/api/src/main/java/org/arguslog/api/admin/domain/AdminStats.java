package org.arguslog.api.admin.domain;

import java.util.Map;

/** Snapshot returned by {@code GET /api/v1/admin/stats}. */
public record AdminStats(
    long totalUsers,
    long totalOrgs,
    long totalProjects,
    long totalIssues,
    Map<String, Long> orgsByPlan,
    long activeBonusGrants,
    long events7d,
    long events30d) {}
