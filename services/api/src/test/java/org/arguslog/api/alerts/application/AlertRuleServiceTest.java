package org.arguslog.api.alerts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Optional;
import org.arguslog.api.alerts.application.AlertRuleUseCase.InvalidAlertRuleException;
import org.arguslog.api.alerts.application.port.AlertRuleRepository;
import org.arguslog.api.alerts.application.port.AlertRuleWriteRepository;
import org.arguslog.api.alerts.domain.AlertRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlertRuleServiceTest {

  @Mock AlertRuleRepository repository;
  @Mock AlertRuleWriteRepository writes;

  AlertRuleService service;
  ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    service = new AlertRuleService(repository, writes);
  }

  @Test
  void minimalCreateGoesThroughWithEmptyConditionsAndOneDestination() {
    JsonNode conditions = mapper.createObjectNode();
    ObjectNode actions = mapper.createObjectNode();
    actions.putArray("destinationIds").add(7);

    when(writes.create(eq(101L), eq("ops"), any(), any(), eq(300), eq(true)))
        .thenReturn(sample(101L, "ops"));

    service.create(101L, "ops", conditions, actions, 300, true);

    verify(writes).create(eq(101L), eq("ops"), any(), any(), eq(300), eq(true));
  }

  @Test
  void blankNameIsRejected() {
    ObjectNode actions = mapper.createObjectNode();
    actions.putArray("destinationIds").add(7);
    assertThatThrownBy(
            () -> service.create(101L, "  ", mapper.createObjectNode(), actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("name");
  }

  @Test
  void conditionsMustBeJsonObject() {
    ObjectNode actions = mapper.createObjectNode();
    actions.putArray("destinationIds").add(7);
    assertThatThrownBy(
            () -> service.create(101L, "x", mapper.createArrayNode(), actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("conditions");
  }

  @Test
  void unknownLevelEnumIsRejected() {
    ObjectNode conditions = mapper.createObjectNode();
    conditions.putObject("level").putArray("in").add("critical");
    ObjectNode actions = mapper.createObjectNode();
    actions.putArray("destinationIds").add(7);

    assertThatThrownBy(() -> service.create(101L, "x", conditions, actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("level.in");
  }

  @Test
  void emptyTagInIsRejected() {
    ObjectNode conditions = mapper.createObjectNode();
    ObjectNode tag = conditions.putObject("tag");
    tag.put("key", "env");
    tag.putArray("in"); // empty
    ObjectNode actions = mapper.createObjectNode();
    actions.putArray("destinationIds").add(7);

    assertThatThrownBy(() -> service.create(101L, "x", conditions, actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("tag.in");
  }

  @Test
  void firstSeenWindowMustBeIso8601() {
    ObjectNode conditions = mapper.createObjectNode().put("firstSeenWindow", "5min");
    ObjectNode actions = mapper.createObjectNode();
    actions.putArray("destinationIds").add(7);

    assertThatThrownBy(() -> service.create(101L, "x", conditions, actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("ISO-8601");
  }

  @Test
  void occurrenceThresholdMustBePositive() {
    ObjectNode conditions = mapper.createObjectNode().put("occurrenceThreshold", 0);
    ObjectNode actions = mapper.createObjectNode();
    actions.putArray("destinationIds").add(7);

    assertThatThrownBy(() -> service.create(101L, "x", conditions, actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("occurrenceThreshold");
  }

  @Test
  void emptyDestinationIdsIsRejected() {
    ObjectNode actions = mapper.createObjectNode();
    actions.putArray("destinationIds"); // empty
    assertThatThrownBy(
            () -> service.create(101L, "x", mapper.createObjectNode(), actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("destinationIds");
  }

  @Test
  void tooManyDestinationIdsIsRejected() {
    ObjectNode actions = mapper.createObjectNode();
    var arr = actions.putArray("destinationIds");
    for (int i = 1; i <= AlertRuleService.MAX_DESTINATIONS + 1; i++) arr.add(i);
    assertThatThrownBy(
            () -> service.create(101L, "x", mapper.createObjectNode(), actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("capped");
  }

  @Test
  void throttleClampsBelowMin() {
    assertThat(AlertRuleService.clampThrottle(5)).isEqualTo(AlertRuleService.MIN_THROTTLE);
  }

  @Test
  void throttleClampsAboveMax() {
    assertThat(AlertRuleService.clampThrottle(99_999)).isEqualTo(AlertRuleService.MAX_THROTTLE);
  }

  @Test
  void updateReturnsEmptyForUnknownRuleAndDoesNotTouchRepo() {
    when(repository.find(101L, 999L)).thenReturn(Optional.empty());

    Optional<AlertRule> result =
        service.update(
            101L,
            999L,
            "x",
            mapper.createObjectNode(),
            mapper.createObjectNode().set("destinationIds", mapper.createArrayNode().add(7)),
            300,
            true);

    assertThat(result).isEmpty();
    verify(writes, never())
        .update(anyLong(), anyLong(), anyString(), any(), any(), anyInt(), anyBoolean());
  }

  @Test
  void deletePassesThrough() {
    when(writes.delete(101L, 7L)).thenReturn(true);
    assertThat(service.delete(101L, 7L)).isTrue();
  }

  private AlertRule sample(long projectId, String name) {
    return new AlertRule(
        7L,
        projectId,
        name,
        mapper.createObjectNode(),
        mapper.createObjectNode().set("destinationIds", mapper.createArrayNode().add(7)),
        300,
        true,
        Instant.parse("2026-05-05T12:00:00Z"));
  }
}
