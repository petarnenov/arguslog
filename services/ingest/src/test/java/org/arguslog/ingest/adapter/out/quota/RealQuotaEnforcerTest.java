package org.arguslog.ingest.adapter.out.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.arguslog.ingest.application.port.MonthlyQuotaCounter;
import org.arguslog.ingest.application.port.ProjectQuotaContext;
import org.arguslog.ingest.application.port.ProjectQuotaContext.Context;
import org.arguslog.ingest.application.port.QuotaEnforcer.Decision;
import org.arguslog.billing.PlanTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RealQuotaEnforcerTest {

  @Mock Bucket4jBurstLimiter burst;
  @Mock ProjectQuotaContext context;
  @Mock MonthlyQuotaCounter monthly;

  RealQuotaEnforcer enforcer;

  // Mid-month → period_start derives to 2026-05-01.
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-15T12:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate PERIOD = LocalDate.of(2026, 5, 1);

  @BeforeEach
  void setUp() {
    enforcer = new RealQuotaEnforcer(burst, context, monthly, CLOCK);
  }

  @Test
  void allowsWhenBurstAndMonthlyBothPass() {
    when(burst.tryConsume(101L)).thenReturn(true);
    when(context.lookup(101L)).thenReturn(Optional.of(new Context(1L, PlanTier.PRO)));
    when(monthly.tryConsume(1L, PERIOD, 100_000L)).thenReturn(true);

    assertThat(enforcer.tryConsume(101L)).isEqualTo(Decision.ALLOW);
  }

  @Test
  void rateLimitedSkipsTheDbRoundTrip() {
    when(burst.tryConsume(101L)).thenReturn(false);

    assertThat(enforcer.tryConsume(101L)).isEqualTo(Decision.RATE_LIMITED);
    // Cheap reject — no need to look up the org or pay the UPSERT cost.
    verify(context, never()).lookup(anyLong());
    verify(monthly, never()).tryConsume(anyLong(), any(), anyLong());
  }

  @Test
  void quotaExceededReportedWhenMonthlyCounterRejects() {
    when(burst.tryConsume(101L)).thenReturn(true);
    when(context.lookup(101L)).thenReturn(Optional.of(new Context(1L, PlanTier.FREE)));
    when(monthly.tryConsume(1L, PERIOD, 5_000L)).thenReturn(false);

    assertThat(enforcer.tryConsume(101L)).isEqualTo(Decision.QUOTA_EXCEEDED);
  }

  @Test
  void unknownProjectAllowsButSkipsMonthlyConsume() {
    when(burst.tryConsume(101L)).thenReturn(true);
    when(context.lookup(101L)).thenReturn(Optional.empty());

    assertThat(enforcer.tryConsume(101L)).isEqualTo(Decision.ALLOW);
    verify(monthly, never()).tryConsume(anyLong(), any(), anyLong());
  }

  @Test
  void enterpriseUsesItsOwnEffectivelyUnlimitedCap() {
    when(burst.tryConsume(101L)).thenReturn(true);
    when(context.lookup(101L)).thenReturn(Optional.of(new Context(1L, PlanTier.ENTERPRISE)));
    when(monthly.tryConsume(eq(1L), eq(PERIOD), eq(Long.MAX_VALUE))).thenReturn(true);

    assertThat(enforcer.tryConsume(101L)).isEqualTo(Decision.ALLOW);
  }
}
