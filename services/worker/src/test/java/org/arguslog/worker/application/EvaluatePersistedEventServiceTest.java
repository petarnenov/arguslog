package org.arguslog.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.arguslog.worker.application.port.AlertRuleRepository;
import org.arguslog.worker.domain.AlertRule;
import org.arguslog.worker.domain.PersistedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvaluatePersistedEventServiceTest {

  @Mock AlertRuleRepository rules;

  EvaluatePersistedEventService service;
  PersistedEvent event;
  ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    service =
        new EvaluatePersistedEventService(
            rules,
            new RuleEvaluator(Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC)));
    event =
        new PersistedEvent(
            7L,
            101L,
            "error",
            true,
            1,
            Instant.parse("2026-05-05T11:59:00Z"),
            Instant.parse("2026-05-05T12:00:00Z"));
  }

  @Test
  void noRulesReturnsEmpty() {
    when(rules.enabledForProject(101L)).thenReturn(List.of());
    assertThat(service.evaluate(event)).isEmpty();
  }

  @Test
  void filtersByConditionsAndReturnsOnlyMatches() throws Exception {
    AlertRule fires = rule(1, "{\"level\":{\"in\":[\"error\"]}}");
    AlertRule skips = rule(2, "{\"level\":{\"in\":[\"fatal\"]}}");
    AlertRule alsoFires = rule(3, "{}");
    when(rules.enabledForProject(101L)).thenReturn(List.of(fires, skips, alsoFires));

    List<AlertRule> matches = service.evaluate(event);

    assertThat(matches).extracting(AlertRule::id).containsExactly(1L, 3L);
  }

  private AlertRule rule(long id, String conditionsJson) throws Exception {
    JsonNode conditions = mapper.readTree(conditionsJson);
    JsonNode actions = mapper.readTree("{\"destinationIds\":[1]}");
    return new AlertRule(id, 101L, conditions, actions, 300);
  }
}
