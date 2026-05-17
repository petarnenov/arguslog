package org.arguslog.worker.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
 *   <li>{@code tag: {key, in: [...]} } — match against an SDK-supplied tag on the event payload
 * </ul>
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
    if (!matchesTag(conditions.get("tag"), event.tags())) return false;
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

  /**
   * Tag clause: {@code {"key":"env","in":["production","staging"]}}. Match if the event carries a
   * tag with the same key and a value inside the {@code in} array. Missing key on the event is a
   * non-match — the rule was clearly asking for environment scoping and the event didn't supply
   * one. Malformed clauses (no key, empty array) tolerate as match-all so a worker reading from a
   * corrupted row doesn't drop legit traffic; the api validator is the real gate.
   */
  private static boolean matchesTag(JsonNode tag, Map<String, String> eventTags) {
    if (tag == null || tag.isNull()) return true;
    JsonNode keyNode = tag.path("key");
    JsonNode inNode = tag.path("in");
    if (!keyNode.isTextual() || keyNode.asText().isBlank()) return true;
    if (!inNode.isArray() || inNode.isEmpty()) return true;
    String key = keyNode.asText();
    String value = eventTags == null ? null : eventTags.get(key);
    if (value == null) return false;
    for (JsonNode v : inNode) {
      if (v.isTextual() && v.asText().equals(value)) return true;
    }
    return false;
  }
}
