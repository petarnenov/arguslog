package org.arguslog.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.arguslog.worker.application.port.AlertContextResolver;
import org.arguslog.worker.application.port.AlertContextResolver.Resolved;
import org.arguslog.worker.application.port.AlertDestinationRepository;
import org.arguslog.worker.application.port.AlertDispatcher;
import org.arguslog.worker.domain.Alert;
import org.arguslog.worker.domain.AlertDestination;
import org.arguslog.worker.domain.AlertDestination.Kind;
import org.arguslog.worker.domain.AlertRule;
import org.arguslog.worker.domain.PersistedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DispatchAlertServiceTest {

  @Mock AlertDestinationRepository destinations;
  @Mock AlertContextResolver resolver;

  private final ObjectMapper mapper = new ObjectMapper();

  private final PersistedEvent event =
      new PersistedEvent(
          7L,
          101L,
          "error",
          true,
          3L,
          Instant.parse("2026-05-05T11:59:00Z"),
          Instant.parse("2026-05-05T12:00:00Z"));

  private final Resolved ctx = new Resolved("acme", "web", "TypeError: x");

  private final RecordingDispatcher telegram = new RecordingDispatcher(Kind.TELEGRAM);
  private final RecordingDispatcher slack = new RecordingDispatcher(Kind.SLACK);

  DispatchAlertService service;

  @BeforeEach
  void setUp() {
    service = new DispatchAlertService(destinations, resolver, List.of(telegram, slack));
  }

  @Test
  void noDestinationsInActionsShortCircuits() throws Exception {
    int sent = service.dispatch(rule(1, "{}"), event);
    assertThat(sent).isZero();
    verify(destinations, never()).findAllById(any());
    verify(resolver, never()).resolve(any());
  }

  @Test
  void unresolvableContextSkipsDispatch() throws Exception {
    when(resolver.resolve(event)).thenReturn(Optional.empty());
    int sent = service.dispatch(rule(1, "{\"destinationIds\":[10]}"), event);
    assertThat(sent).isZero();
    verify(destinations, never()).findAllById(any());
  }

  @Test
  void deletedDestinationsAreSkippedSilently() throws Exception {
    when(resolver.resolve(event)).thenReturn(Optional.of(ctx));
    when(destinations.findAllById(List.of(10L, 20L))).thenReturn(List.of());
    int sent = service.dispatch(rule(1, "{\"destinationIds\":[10,20]}"), event);
    assertThat(sent).isZero();
    assertThat(telegram.calls).isEmpty();
  }

  @Test
  void routesEachDestinationToItsKindDispatcher() throws Exception {
    AlertDestination tg = new AlertDestination(10L, 1L, Kind.TELEGRAM, "ops", "{}");
    AlertDestination sl = new AlertDestination(20L, 1L, Kind.SLACK, "alerts", "{}");
    when(resolver.resolve(event)).thenReturn(Optional.of(ctx));
    when(destinations.findAllById(List.of(10L, 20L))).thenReturn(List.of(tg, sl));

    int sent = service.dispatch(rule(1, "{\"destinationIds\":[10,20]}"), event);

    assertThat(sent).isEqualTo(2);
    assertThat(telegram.calls).hasSize(1);
    assertThat(slack.calls).hasSize(1);

    Alert delivered = telegram.calls.get(0);
    assertThat(delivered.ruleId()).isEqualTo(1L);
    assertThat(delivered.orgSlug()).isEqualTo("acme");
    assertThat(delivered.projectSlug()).isEqualTo("web");
    assertThat(delivered.issueTitle()).isEqualTo("TypeError: x");
    assertThat(delivered.occurrenceCount()).isEqualTo(3L);
  }

  @Test
  void destinationKindWithoutDispatcherIsSkipped() throws Exception {
    AlertDestination email = new AlertDestination(30L, 1L, Kind.EMAIL, "ops@", "{}");
    when(resolver.resolve(event)).thenReturn(Optional.of(ctx));
    when(destinations.findAllById(List.of(30L))).thenReturn(List.of(email));

    int sent = service.dispatch(rule(1, "{\"destinationIds\":[30]}"), event);

    assertThat(sent).isZero();
    assertThat(telegram.calls).isEmpty();
    assertThat(slack.calls).isEmpty();
  }

  @Test
  void crashingDispatcherDoesNotPoisonTheRestOfFanOut() throws Exception {
    AlertDispatcher crashy =
        new AlertDispatcher() {
          @Override
          public Kind kind() {
            return Kind.WEBHOOK;
          }

          @Override
          public void dispatch(Alert alert, AlertDestination destination) {
            throw new RuntimeException("boom");
          }
        };
    DispatchAlertService svc =
        new DispatchAlertService(destinations, resolver, List.of(crashy, telegram));
    AlertDestination wb = new AlertDestination(40L, 1L, Kind.WEBHOOK, "wh", "{}");
    AlertDestination tg = new AlertDestination(10L, 1L, Kind.TELEGRAM, "ops", "{}");
    when(resolver.resolve(event)).thenReturn(Optional.of(ctx));
    when(destinations.findAllById(List.of(40L, 10L))).thenReturn(List.of(wb, tg));

    int sent = svc.dispatch(rule(1, "{\"destinationIds\":[40,10]}"), event);

    assertThat(sent).isEqualTo(2);
    assertThat(telegram.calls).hasSize(1); // telegram still ran after webhook crashed
  }

  @Test
  void registeringTwoDispatchersForSameKindFailsFast() {
    AlertDispatcher dup = new RecordingDispatcher(Kind.TELEGRAM);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new DispatchAlertService(destinations, resolver, List.of(telegram, dup)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void preservesDestinationOrderFromActions() throws Exception {
    AlertDestination first = new AlertDestination(20L, 1L, Kind.TELEGRAM, "second-id-first", "{}");
    AlertDestination second = new AlertDestination(10L, 1L, Kind.TELEGRAM, "first-id-second", "{}");
    when(resolver.resolve(event)).thenReturn(Optional.of(ctx));
    when(destinations.findAllById(List.of(20L, 10L))).thenReturn(List.of(first, second));

    service.dispatch(rule(1, "{\"destinationIds\":[20,10]}"), event);

    verify(destinations).findAllById(List.of(20L, 10L));
    assertThat(telegram.calls).extracting(Alert::ruleId).containsExactly(1L, 1L);
  }

  private AlertRule rule(long id, String actionsJson) throws Exception {
    JsonNode actions = mapper.readTree(actionsJson);
    return new AlertRule(id, 101L, "rule-" + id, mapper.readTree("{}"), actions, 300);
  }

  private static class RecordingDispatcher implements AlertDispatcher {
    final Kind kind;
    final java.util.List<Alert> calls = new java.util.ArrayList<>();

    RecordingDispatcher(Kind kind) {
      this.kind = kind;
    }

    @Override
    public Kind kind() {
      return kind;
    }

    @Override
    public void dispatch(Alert alert, AlertDestination destination) {
      calls.add(alert);
    }
  }
}
