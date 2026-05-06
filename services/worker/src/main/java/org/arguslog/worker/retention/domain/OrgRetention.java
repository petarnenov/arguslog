package org.arguslog.worker.retention.domain;

import java.time.Duration;

/**
 * Snapshot of an org's effective retention. Computed by the {@code OrgRetentionRepository} from
 * {@code organizations.plan} + {@code organizations.retention_days_override} so the purge service
 * doesn't have to know which one took effect.
 */
public record OrgRetention(long orgId, Duration effectiveRetention) {}
