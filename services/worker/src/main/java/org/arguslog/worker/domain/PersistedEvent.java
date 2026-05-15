package org.arguslog.worker.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Snapshot the worker's persistence step publishes to {@code events:persisted} for the rule
 * evaluator. Carries the fields the conditions DSL needs (level / occurrence_count /
 * first_seen_at) plus enough provenance ({@code issueId} / {@code projectId}) for the dispatcher
 * to render an alert.
 *
 * <p>{@code tags} are extracted from the SDK payload at hand-off time so tag-clause rules can
 * match without the dispatcher needing to re-fetch the raw event. May be empty when the payload
 * carries no tags or is malformed; rule evaluator treats absent / missing keys as "no match" for
 * tag clauses (the rest of the rule still applies via AND-semantics).
 */
public record PersistedEvent(
    long issueId,
    long projectId,
    String level,
    boolean newIssue,
    long occurrenceCount,
    Instant firstSeenAt,
    Instant lastSeenAt,
    Map<String, String> tags) {

  public PersistedEvent {
    tags = tags == null ? Map.of() : Map.copyOf(tags);
  }

  /**
   * Convenience overload — older call sites that don't yet thread tags through. Leaves tags
   * empty so tag-clause rules don't fire spuriously on legacy plumbing.
   */
  public PersistedEvent(
      long issueId,
      long projectId,
      String level,
      boolean newIssue,
      long occurrenceCount,
      Instant firstSeenAt,
      Instant lastSeenAt) {
    this(issueId, projectId, level, newIssue, occurrenceCount, firstSeenAt, lastSeenAt, Map.of());
  }
}
