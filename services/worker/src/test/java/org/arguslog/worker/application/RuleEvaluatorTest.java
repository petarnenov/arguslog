package org.arguslog.worker.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.arguslog.worker.domain.AlertRule;
import org.arguslog.worker.domain.PersistedEvent;
import org.junit.jupiter.api.Test;

class RuleEvaluatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-05-05T12:00:00Z");
  private final RuleEvaluator evaluator = new RuleEvaluator(Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void emptyConditionsAlwaysMatch() {
    assertThat(evaluator.matches(rule("{}"), event("error", 1, NOW))).isTrue();
  }

  @Test
  void levelInMatches() {
    assertThat(
            evaluator.matches(
                rule("{\"level\":{\"in\":[\"fatal\",\"error\"]}}"), event("error", 1, NOW)))
        .isTrue();
    assertThat(evaluator.matches(rule("{\"level\":{\"in\":[\"fatal\"]}}"), event("error", 1, NOW)))
        .isFalse();
  }

  @Test
  void occurrenceThresholdGate() {
    AlertRule r = rule("{\"occurrenceThreshold\":100}");
    assertThat(evaluator.matches(r, event("error", 99, NOW))).isFalse();
    assertThat(evaluator.matches(r, event("error", 100, NOW))).isTrue();
    assertThat(evaluator.matches(r, event("error", 250, NOW))).isTrue();
  }

  @Test
  void firstSeenWindowKeepsFreshIssuesAndDropsAged() {
    AlertRule r = rule("{\"firstSeenWindow\":\"PT5M\"}");
    assertThat(evaluator.matches(r, event("error", 1, NOW.minusSeconds(60)))).isTrue(); // 1 min old
    assertThat(evaluator.matches(r, event("error", 1, NOW.minusSeconds(600)))).isFalse(); // 10 min
  }

  @Test
  void allClausesMustMatch() {
    AlertRule r = rule("{\"level\":{\"in\":[\"fatal\"]},\"occurrenceThreshold\":10}");
    assertThat(evaluator.matches(r, event("fatal", 100, NOW))).isTrue();
    assertThat(evaluator.matches(r, event("fatal", 1, NOW))).isFalse(); // threshold
    assertThat(evaluator.matches(r, event("error", 100, NOW))).isFalse(); // level
  }

  @Test
  void tagClauseRejectsEventsWithNoTagsAtAll() {
    AlertRule r = rule("{\"tag\":{\"key\":\"env\",\"in\":[\"prod\"]}}");
    // Event with empty tag map — the rule clearly asks for an environment scope; if the SDK
    // didn't send any tags, that's a non-match (rather than the legacy "tag never fires").
    assertThat(evaluator.matches(r, event("error", 1, NOW))).isFalse();
  }

  @Test
  void unknownClauseIsIgnoredForwardCompat() {
    // api can ship a new clause before the worker learns it; rule over-matches until catch-up.
    AlertRule r = rule("{\"newSemantic\":{\"foo\":\"bar\"}}");
    assertThat(evaluator.matches(r, event("error", 1, NOW))).isTrue();
  }

  @Test
  void malformedDurationDoesNotCrashEvaluator() {
    AlertRule r = rule("{\"firstSeenWindow\":\"5min\"}"); // not ISO-8601
    assertThat(evaluator.matches(r, event("error", 1, NOW))).isTrue();
  }

  private AlertRule rule(String conditionsJson) {
    try {
      JsonNode conditions = MAPPER.readTree(conditionsJson);
      JsonNode actions = MAPPER.readTree("{\"destinationIds\":[1]}");
      return new AlertRule(7L, 101L, "test-rule", conditions, actions, 300);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private PersistedEvent event(String level, long occurrenceCount, Instant firstSeenAt) {
    return new PersistedEvent(7L, 101L, level, false, occurrenceCount, firstSeenAt, NOW);
  }

  private PersistedEvent eventWithTags(java.util.Map<String, String> tags) {
    return new PersistedEvent(7L, 101L, "error", false, 1, NOW, NOW, tags);
  }

  @org.junit.jupiter.api.Test
  void tagClauseMatchesWhenEventCarriesTagValueInList() {
    AlertRule r = rule("{\"tag\":{\"key\":\"env\",\"in\":[\"production\",\"staging\"]}}");
    assertThat(evaluator.matches(r, eventWithTags(java.util.Map.of("env", "production")))).isTrue();
    assertThat(evaluator.matches(r, eventWithTags(java.util.Map.of("env", "staging")))).isTrue();
  }

  @org.junit.jupiter.api.Test
  void tagClauseRejectsWhenEventCarriesDifferentValue() {
    AlertRule r = rule("{\"tag\":{\"key\":\"env\",\"in\":[\"production\"]}}");
    assertThat(evaluator.matches(r, eventWithTags(java.util.Map.of("env", "development"))))
        .isFalse();
  }

  @org.junit.jupiter.api.Test
  void tagClauseRejectsWhenEventMissingTheKey() {
    AlertRule r = rule("{\"tag\":{\"key\":\"env\",\"in\":[\"production\"]}}");
    assertThat(evaluator.matches(r, eventWithTags(java.util.Map.of("region", "us-east-1"))))
        .isFalse();
    assertThat(evaluator.matches(r, eventWithTags(java.util.Map.of()))).isFalse();
  }

  @org.junit.jupiter.api.Test
  void tagClauseAndsWithLevelClause() {
    AlertRule r =
        rule("{\"level\":{\"in\":[\"error\"]},\"tag\":{\"key\":\"env\",\"in\":[\"production\"]}}");
    // level match + tag match → fire
    assertThat(evaluator.matches(r, eventWithTags(java.util.Map.of("env", "production")))).isTrue();
    // tag match + wrong level → no fire
    PersistedEvent infoLevel =
        new PersistedEvent(
            7L, 101L, "info", false, 1, NOW, NOW, java.util.Map.of("env", "production"));
    assertThat(evaluator.matches(r, infoLevel)).isFalse();
  }
}
