package org.arguslog.worker.retention.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.arguslog.worker.retention.application.port.OrgRetentionRepository;
import org.arguslog.worker.retention.application.port.RetentionPurgeRepository;
import org.arguslog.worker.retention.domain.OrgRetention;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetentionPurgeServiceTest {

  private static final Instant NOW = Instant.parse("2026-05-06T14:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock OrgRetentionRepository orgs;
  @Mock RetentionPurgeRepository purger;

  @Test
  void noOrgsBelowFloorIsANoOp() {
    when(orgs.orgsBelowFloor(any())).thenReturn(List.of());
    RetentionPurgeService service = new RetentionPurgeService(orgs, purger, FIXED, false, 100);

    assertThat(service.runOnce()).isZero();
    verify(purger, never()).purgeBatch(anyLong(), any(), anyInt());
  }

  @Test
  void dryRunCountsButDoesNotDelete() {
    when(orgs.orgsBelowFloor(any())).thenReturn(List.of(new OrgRetention(1L, Duration.ofDays(30))));
    Instant cutoff = NOW.minus(Duration.ofDays(30));
    when(purger.countEligible(1L, cutoff)).thenReturn(42L);

    RetentionPurgeService service = new RetentionPurgeService(orgs, purger, FIXED, true, 100);

    assertThat(service.runOnce()).isEqualTo(-1L);
    verify(purger).countEligible(1L, cutoff);
    verify(purger, never()).purgeBatch(anyLong(), any(), anyInt());
  }

  @Test
  void looplsUntilBatchReturnsLessThanLimit() {
    when(orgs.orgsBelowFloor(any())).thenReturn(List.of(new OrgRetention(1L, Duration.ofDays(30))));
    Instant cutoff = NOW.minus(Duration.ofDays(30));
    // 100, 100, 30 → terminator is the third short batch
    when(purger.purgeBatch(eq(1L), eq(cutoff), eq(100))).thenReturn(100, 100, 30);

    RetentionPurgeService service = new RetentionPurgeService(orgs, purger, FIXED, false, 100);

    assertThat(service.runOnce()).isEqualTo(230L);
    verify(purger, times(3)).purgeBatch(1L, cutoff, 100);
  }

  @Test
  void perOrgCutoffComputedFromEachOrgsRetention() {
    OrgRetention freeOrg = new OrgRetention(1L, Duration.ofDays(30));
    OrgRetention midOrg = new OrgRetention(2L, Duration.ofDays(60));
    when(orgs.orgsBelowFloor(any())).thenReturn(List.of(freeOrg, midOrg));

    Instant freeCutoff = NOW.minus(Duration.ofDays(30));
    Instant midCutoff = NOW.minus(Duration.ofDays(60));
    when(purger.purgeBatch(1L, freeCutoff, 100)).thenReturn(5);
    when(purger.purgeBatch(2L, midCutoff, 100)).thenReturn(0);

    RetentionPurgeService service = new RetentionPurgeService(orgs, purger, FIXED, false, 100);

    assertThat(service.runOnce()).isEqualTo(5L);
    verify(purger).purgeBatch(1L, freeCutoff, 100);
    verify(purger).purgeBatch(2L, midCutoff, 100);
  }

  @Test
  void singleShortBatchExitsAfterOneCall() {
    when(orgs.orgsBelowFloor(any())).thenReturn(List.of(new OrgRetention(1L, Duration.ofDays(30))));
    Instant cutoff = NOW.minus(Duration.ofDays(30));
    when(purger.purgeBatch(1L, cutoff, 100)).thenReturn(7);

    RetentionPurgeService service = new RetentionPurgeService(orgs, purger, FIXED, false, 100);

    assertThat(service.runOnce()).isEqualTo(7L);
    verify(purger, times(1)).purgeBatch(1L, cutoff, 100);
  }
}
