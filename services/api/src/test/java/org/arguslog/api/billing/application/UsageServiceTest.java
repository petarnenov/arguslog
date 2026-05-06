package org.arguslog.api.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.arguslog.api.billing.application.UsageUseCase.UsageSnapshot;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.billing.application.port.UsageRepository;
import org.arguslog.api.billing.domain.PlanTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UsageServiceTest {

  @Mock OrgPlanRepository plans;
  @Mock UsageRepository usage;

  private UsageService service;

  // Pinned mid-month so the period_start derivation is unambiguous.
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-15T12:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate PERIOD = LocalDate.of(2026, 5, 1);

  @BeforeEach
  void setUp() {
    service = new UsageService(plans, usage, CLOCK);
  }

  @Test
  void unknownOrgReturnsEmpty() {
    when(plans.findPlan(99L)).thenReturn(Optional.empty());
    assertThat(service.snapshot(99L)).isEmpty();
  }

  @Test
  void usageBelowCapReportsRatioAndNotExceeded() {
    when(plans.findPlan(1L)).thenReturn(Optional.of(PlanTier.PRO));
    when(usage.currentEventCount(1L, PERIOD)).thenReturn(25_000L);

    UsageSnapshot snap = service.snapshot(1L).orElseThrow();
    assertThat(snap.plan()).isEqualTo(PlanTier.PRO);
    assertThat(snap.eventsUsed()).isEqualTo(25_000L);
    assertThat(snap.eventCap()).isEqualTo(100_000L);
    assertThat(snap.ratio()).isEqualTo(0.25);
    assertThat(snap.exceeded()).isFalse();
  }

  @Test
  void usageAtOrAboveCapReportsExceeded() {
    when(plans.findPlan(1L)).thenReturn(Optional.of(PlanTier.FREE));
    when(usage.currentEventCount(1L, PERIOD)).thenReturn(5_000L);

    UsageSnapshot snap = service.snapshot(1L).orElseThrow();
    assertThat(snap.exceeded()).isTrue();
    assertThat(snap.ratio()).isEqualTo(1.0);
  }

  @Test
  void usageDeriveUtcMonthStart() {
    // Service must derive period_start from UTC; verifies the clock is consulted, not "today".
    when(plans.findPlan(1L)).thenReturn(Optional.of(PlanTier.FREE));
    when(usage.currentEventCount(1L, LocalDate.of(2026, 5, 1))).thenReturn(1L);

    UsageSnapshot snap = service.snapshot(1L).orElseThrow();
    assertThat(snap.eventsUsed()).isEqualTo(1L);
  }
}
