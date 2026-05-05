package org.arguslog.worker.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.arguslog.worker.domain.AlertRule;
import org.arguslog.worker.domain.PersistedEvent;
import org.springframework.stereotype.Component;

/**
 * Pure function: a rule's conditions × a persisted event → match? AND-semantics across clauses.
 *
 * <p>Today's clauses (conditions DSL):
 *
 * <ul>
 *   <li>{@code level.in: [...] } — match issue level
 *   <li>{@code firstSeenWindow: ISO-8601 } — only fire while the issue is fresh
 *   <li>{@code occurrenceThreshold: N } — only fire after N occurrences
 * </ul>
 *
 * <p>{@code tag.{key,in}} is accepted at the api but evaluated against {@code null} (no payload
 * tags reach this layer yet); rules using tag never fire until the payload is wired through. Same
 * "extend the worker, no api PR" stance as the rest of the DSL.
 *
 * <p>Unknown clauses are ignored on read (forward-compat: api can ship a new clause before the
 * worker learns it; the rule just over-matches until the worker catches up).
 */
@Component
public final class RuleEvaluator {

  private final Clock clock;

  public RuleEvaluator(Clock clock) {
    this.clock = clock;
  }

  public boolean matches(AlertRule rule, PersistedEvent event) {
    JsonNode conditions = rule.conditions();
    if (conditions == null || conditions.isNull()) return true;

    if (!matchesLevel(conditions.get("level"), event.level())) return false;
    if (!matchesFirstSeenWindow(conditions.get("firstSeenWindow"), event.firstSeenAt())) {
      return false;
    }
    if (!matchesOccurrenceThreshold(
        conditions.get("occurrenceThreshold"), event.occurrenceCount())) {
      return false;
    }
    if (!matchesTag(conditions.get("tag"))) return false;
    return true;
  }

  private static boolean matchesLevel(JsonNode level, String eventLevel) {
    if (level == null || level.isNull()) return true;
    JsonNode in = level.path("in");
    if (!in.isArray() || in.isEmpty()) return true; // tolerated; api validation should have caught
    for (JsonNode v : in) {
      if (v.isTextual() && v.asText().equalsIgnoreCase(eventLevel)) return true;
    }
    return false;
  }

  private boolean matchesFirstSeenWindow(JsonNode window, Instant firstSeenAt) {
    if (window == null || window.isNull() || !window.isTextual()) return true;
    Duration max;
    try {
      max = Duration.parse(window.asText());
    } catch (RuntimeException e) {
      return true; // be lenient on malformed values; api validation is the gate
    }
    Instant cutoff = Instant.now(clock).minus(max);
    return !firstSeenAt.isBefore(cutoff);
  }

  private static boolean matchesOccurrenceThreshold(JsonNode threshold, long occurrenceCount) {
    if (threshold == null || threshold.isNull()) return true;
    if (!threshold.canConvertToLong()) return true;
    return occurrenceCount >= threshold.asLong();
  }

  private static boolean matchesTag(JsonNode tag) {
    if (tag == null || tag.isNull()) return true;
    // Payload tags aren't piped to PersistedEvent yet — a rule that uses tag never fires today.
    // Documented gap; lifted when the dispatcher gets the full payload.
    return false;
  }
}
