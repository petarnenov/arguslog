package org.arguslog.worker.domain;

import java.time.Instant;

/**
 * Snapshot the worker's persistence step publishes to {@code events:persisted} for the rule
 * evaluator. The fields are exactly what the conditions DSL needs (level / occurrence_count /
 * first_seen_at) plus enough provenance ({@code issueId} / {@code projectId}) for the dispatcher to
 * render an alert. Tag-based rules will read tags from the event payload separately when
 * implemented.
 */
public record PersistedEvent(
    long issueId,
    long projectId,
    String level,
    boolean newIssue,
    long occurrenceCount,
    Instant firstSeenAt,
    Instant lastSeenAt) {}
