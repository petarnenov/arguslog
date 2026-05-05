package org.arguslog.ingest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.arguslog.ingest.application.IngestEventUseCase.Command;
import org.arguslog.ingest.application.IngestEventUseCase.Result;
import org.arguslog.ingest.application.port.EventStreamPublisher;
import org.arguslog.ingest.application.port.ProjectAuthenticator;
import org.arguslog.ingest.application.port.QuotaEnforcer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IngestEventServiceTest {

  @Mock ProjectAuthenticator authenticator;
  @Mock QuotaEnforcer quotaEnforcer;
  @Mock EventStreamPublisher publisher;

  IngestEventService service;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC);
    service = new IngestEventService(authenticator, quotaEnforcer, publisher, clock);
  }

  @Test
  void publishesAcceptedEnvelopeWhenAllowed() {
    when(authenticator.authenticate(1L, "key")).thenReturn(Optional.of(1L));
    when(quotaEnforcer.tryConsume(1L)).thenReturn(QuotaEnforcer.Decision.ALLOW);

    Result result = service.ingest(new Command(1L, "key", "{}", "1.2.3.4", "ua"));

    assertThat(result).isInstanceOf(Result.Accepted.class);
    verify(publisher).publish(any());
  }

  @Test
  void rejectsOversizedPayload() {
    String tooBig = "x".repeat(201 * 1024);
    Result result = service.ingest(new Command(1L, "k", tooBig, "ip", "ua"));
    assertThat(result).isInstanceOf(Result.PayloadTooLarge.class);
    verify(publisher, never()).publish(any());
  }

  @Test
  void rejectsUnknownDsn() {
    when(authenticator.authenticate(anyLong(), anyString())).thenReturn(Optional.empty());
    Result result = service.ingest(new Command(1L, "bad", "{}", "ip", "ua"));
    assertThat(result).isInstanceOf(Result.Unauthorized.class);
    verify(publisher, never()).publish(any());
  }

  @Test
  void returnsRateLimitedWhenThrottled() {
    when(authenticator.authenticate(anyLong(), anyString())).thenReturn(Optional.of(1L));
    when(quotaEnforcer.tryConsume(1L)).thenReturn(QuotaEnforcer.Decision.RATE_LIMITED);
    Result result = service.ingest(new Command(1L, "k", "{}", "ip", "ua"));
    assertThat(result).isInstanceOf(Result.RateLimited.class);
    verify(publisher, never()).publish(any());
  }

  @Test
  void returnsQuotaExceededWhenOverQuota() {
    when(authenticator.authenticate(anyLong(), anyString())).thenReturn(Optional.of(1L));
    when(quotaEnforcer.tryConsume(1L)).thenReturn(QuotaEnforcer.Decision.QUOTA_EXCEEDED);
    Result result = service.ingest(new Command(1L, "k", "{}", "ip", "ua"));
    assertThat(result).isInstanceOf(Result.QuotaExceeded.class);
    verify(publisher, never()).publish(any());
  }
}
