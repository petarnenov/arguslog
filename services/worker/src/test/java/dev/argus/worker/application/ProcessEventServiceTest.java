package dev.argus.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.argus.worker.application.ProcessEventUseCase.Result;
import dev.argus.worker.application.port.EventStore;
import dev.argus.worker.application.port.Fingerprinter;
import dev.argus.worker.domain.Fingerprint;
import dev.argus.worker.domain.IncomingEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;

@ExtendWith(MockitoExtension.class)
class ProcessEventServiceTest {

  @Mock Fingerprinter fingerprinter;
  @Mock EventStore store;

  ProcessEventService service;

  IncomingEvent event;
  Fingerprint fingerprint;

  @BeforeEach
  void setUp() {
    service = new ProcessEventService(fingerprinter, store);
    event =
        new IncomingEvent(
            UUID.randomUUID(), 101L, "pk", Instant.parse("2026-05-05T12:00:00Z"), "{}", "ip", "ua");
    fingerprint = new Fingerprint("abc123", "TypeError: x", null, Fingerprint.Level.ERROR);
  }

  @Test
  void persistsAndReportsNewIssueOnFirstEncounter() {
    when(fingerprinter.compute(event.rawPayload())).thenReturn(fingerprint);
    when(store.persist(event, fingerprint)).thenReturn(new EventStore.PersistResult(7L, true));

    Result result = service.process(event);

    assertThat(result).isEqualTo(new Result.Persisted(7L, true));
    verify(store).persist(event, fingerprint);
  }

  @Test
  void reportsExistingIssueOnRepeatEncounter() {
    when(fingerprinter.compute(event.rawPayload())).thenReturn(fingerprint);
    when(store.persist(event, fingerprint)).thenReturn(new EventStore.PersistResult(7L, false));

    Result result = service.process(event);

    assertThat(result).isEqualTo(new Result.Persisted(7L, false));
  }

  @Test
  void unknownFingerprintIsSalvagedNotDropped() {
    Fingerprint unknown =
        new Fingerprint("unknown", "Unparseable event", null, Fingerprint.Level.ERROR);
    when(fingerprinter.compute(event.rawPayload())).thenReturn(unknown);
    when(store.persist(event, unknown)).thenReturn(new EventStore.PersistResult(99L, true));

    Result result = service.process(event);

    assertThat(result).isEqualTo(new Result.SalvagedAsUnknown(99L));
  }

  @Test
  void transientDbFailureMarksEventRetryable() {
    when(fingerprinter.compute(event.rawPayload())).thenReturn(fingerprint);
    when(store.persist(any(), any()))
        .thenThrow(new TransientDataAccessResourceException("connection lost"));

    Result result = service.process(event);

    assertThat(result).isInstanceOf(Result.Retryable.class);
    assertThat(((Result.Retryable) result).reason()).contains("connection lost");
  }
}
