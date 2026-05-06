package org.arguslog.worker.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.arguslog.worker.billing.application.port.PaymentDowngradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentDowngradeServiceTest {

  private static final Instant NOW = Instant.parse("2026-05-13T04:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock PaymentDowngradeRepository repo;

  @Test
  void emptyResultIsANoOp() {
    when(repo.downgradeExpired(NOW)).thenReturn(List.of());

    PaymentDowngradeService service = new PaymentDowngradeService(repo, FIXED);

    assertThat(service.runOnce()).isEmpty();
  }

  @Test
  void returnsAffectedIdsForCallersToAudit() {
    when(repo.downgradeExpired(NOW)).thenReturn(List.of(1L, 7L));

    PaymentDowngradeService service = new PaymentDowngradeService(repo, FIXED);

    assertThat(service.runOnce()).containsExactly(1L, 7L);
  }
}
