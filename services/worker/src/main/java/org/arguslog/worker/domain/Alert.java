package org.arguslog.worker.domain;

import java.time.Instant;

/**
 * One match between a rule and an event, ready to be rendered into a destination-specific message.
 * Carries the project / org context already so a dispatcher does not need to re-query.
 */
public record Alert(
    long ruleId,
    String ruleName,
    long projectId,
    String projectSlug,
    String orgSlug,
    long issueId,
    String issueTitle,
    String level,
    long occurrenceCount,
    Instant firstSeenAt,
    Instant lastSeenAt) {}
