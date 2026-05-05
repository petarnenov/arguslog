package org.arguslog.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.arguslog.worker.application.port.RuleThrottle;
import org.arguslog.worker.domain.AlertRule;
import org.arguslog.worker.domain.PersistedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ThrottlingDispatchAlertServiceTest {

  @Mock DispatchAlertService delegate;
  @Mock RuleThrottle throttle;

  ThrottlingDispatchAlertService service;

  private final ObjectMapper mapper = new ObjectMapper();
  private PersistedEvent event;

  @BeforeEach
  void setUp() {
    service = new ThrottlingDispatchAlertService(delegate, throttle);
    event =
        new PersistedEvent(
            7L,
            101L,
            "error",
            true,
            1L,
            Instant.parse("2026-05-05T11:59:00Z"),
            Instant.parse("2026-05-05T12:00:00Z"));
  }

  @Test
  void firesWhenThrottleSaysGo() throws Exception {
    AlertRule rule = rule(1L, 300);
    when(throttle.tryFire(1L, 300)).thenReturn(true);
    when(delegate.dispatch(rule, event)).thenReturn(2);

    assertThat(service.dispatch(rule, event)).isEqualTo(2);
    verify(delegate).dispatch(rule, event);
  }

  @Test
  void skipsDelegateWhenThrottled() throws Exception {
    AlertRule rule = rule(1L, 300);
    when(throttle.tryFire(1L, 300)).thenReturn(false);

    assertThat(service.dispatch(rule, event)).isZero();
    verify(delegate, never()).dispatch(any(), any());
  }

  @Test
  void zeroThrottleSecondsBypassesGate() throws Exception {
    AlertRule rule = rule(1L, 0);
    when(throttle.tryFire(1L, 0)).thenReturn(true); // RuleThrottle's contract: <=0 => always fire
    when(delegate.dispatch(rule, event)).thenReturn(1);

    assertThat(service.dispatch(rule, event)).isEqualTo(1);
  }

  private AlertRule rule(long id, int throttleSeconds) throws Exception {
    return new AlertRule(
        id,
        101L,
        "rule-" + id,
        mapper.readTree("{}"),
        mapper.readTree("{\"destinationIds\":[1]}"),
        throttleSeconds);
  }
}
