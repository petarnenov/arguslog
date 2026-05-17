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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.alerts.adapter.in.web.dto.AlertRuleActions;
import org.arguslog.api.alerts.adapter.in.web.dto.AlertRuleConditions;
import org.arguslog.api.alerts.adapter.in.web.dto.AlertRuleConditions.LevelClause;
import org.arguslog.api.alerts.adapter.in.web.dto.AlertRuleConditions.TagClause;
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
    service = new AlertRuleService(repository, writes, mapper);
  }

  @Test
  void minimalCreateGoesThroughWithEmptyConditionsAndOneDestination() {
    AlertRuleConditions conditions = new AlertRuleConditions(null, null, null, null);
    AlertRuleActions actions = new AlertRuleActions(List.of(7L));

    when(writes.create(eq(101L), eq("ops"), any(), any(), eq(300), eq(true)))
        .thenReturn(sample(101L, "ops"));

    service.create(101L, "ops", conditions, actions, 300, true);

    verify(writes).create(eq(101L), eq("ops"), any(), any(), eq(300), eq(true));
  }

  @Test
  void blankNameIsRejected() {
    AlertRuleActions actions = new AlertRuleActions(List.of(7L));
    assertThatThrownBy(
            () ->
                service.create(
                    101L,
                    "  ",
                    new AlertRuleConditions(null, null, null, null),
                    actions,
                    300,
                    true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("name");
  }

  @Test
  void conditionsRequiredEvenIfAllClausesEmpty() {
    AlertRuleActions actions = new AlertRuleActions(List.of(7L));
    assertThatThrownBy(() -> service.create(101L, "x", null, actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("conditions");
  }

  @Test
  void unknownLevelEnumIsRejected() {
    AlertRuleConditions conditions =
        new AlertRuleConditions(new LevelClause(List.of("critical")), null, null, null);
    AlertRuleActions actions = new AlertRuleActions(List.of(7L));

    assertThatThrownBy(() -> service.create(101L, "x", conditions, actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("level.in");
  }

  @Test
  void emptyTagInIsRejected() {
    AlertRuleConditions conditions =
        new AlertRuleConditions(null, null, null, new TagClause("env", List.of()));
    AlertRuleActions actions = new AlertRuleActions(List.of(7L));

    assertThatThrownBy(() -> service.create(101L, "x", conditions, actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("tag.in");
  }

  @Test
  void blankTagKeyIsRejected() {
    AlertRuleConditions conditions =
        new AlertRuleConditions(null, null, null, new TagClause("  ", List.of("prod")));
    AlertRuleActions actions = new AlertRuleActions(List.of(7L));

    assertThatThrownBy(() -> service.create(101L, "x", conditions, actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("tag.key");
  }

  @Test
  void firstSeenWindowMustBeIso8601() {
    AlertRuleConditions conditions = new AlertRuleConditions(null, "5min", null, null);
    AlertRuleActions actions = new AlertRuleActions(List.of(7L));

    assertThatThrownBy(() -> service.create(101L, "x", conditions, actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("ISO-8601");
  }

  @Test
  void occurrenceThresholdMustBePositive() {
    AlertRuleConditions conditions = new AlertRuleConditions(null, null, 0, null);
    AlertRuleActions actions = new AlertRuleActions(List.of(7L));

    assertThatThrownBy(() -> service.create(101L, "x", conditions, actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("occurrenceThreshold");
  }

  @Test
  void emptyDestinationIdsIsRejected() {
    AlertRuleActions actions = new AlertRuleActions(List.of());
    assertThatThrownBy(
            () ->
                service.create(
                    101L, "x", new AlertRuleConditions(null, null, null, null), actions, 300, true))
        .isInstanceOf(InvalidAlertRuleException.class)
        .hasMessageContaining("destinationIds");
  }

  @Test
  void tooManyDestinationIdsIsRejected() {
    List<Long> ids =
        java.util.stream.LongStream.rangeClosed(1, AlertRuleService.MAX_DESTINATIONS + 1)
            .boxed()
            .toList();
    AlertRuleActions actions = new AlertRuleActions(ids);
    assertThatThrownBy(
            () ->
                service.create(
                    101L, "x", new AlertRuleConditions(null, null, null, null), actions, 300, true))
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
            new AlertRuleConditions(null, null, null, null),
            new AlertRuleActions(List.of(7L)),
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
