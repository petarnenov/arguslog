package org.arguslog.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.arguslog.worker.application.ProcessEventUseCase.Result;
import org.arguslog.worker.application.port.EventStore;
import org.arguslog.worker.application.port.Fingerprinter;
import org.arguslog.worker.application.port.PersistedEventPublisher;
import org.arguslog.worker.application.port.Symbolicator;
import org.arguslog.worker.domain.Fingerprint;
import org.arguslog.worker.domain.IncomingEvent;
import org.arguslog.worker.domain.PersistedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;

@ExtendWith(MockitoExtension.class)
class ProcessEventServiceTest {

  @Mock Fingerprinter fingerprinter;
  @Mock EventStore store;
  @Mock PersistedEventPublisher publisher;
  @Mock Symbolicator symbolicator;

  ProcessEventService service;

  IncomingEvent event;
  Fingerprint fingerprint;
  EventStore.PersistResult sampleResult;

  @BeforeEach
  void setUp() {
    service =
        new ProcessEventService(
            fingerprinter,
            store,
            publisher,
            symbolicator,
            new com.fasterxml.jackson.databind.ObjectMapper());
    event =
        new IncomingEvent(
            UUID.randomUUID(), 101L, "pk", Instant.parse("2026-05-05T12:00:00Z"), "{}", "ip", "ua");
    // Default: symbolicator is a no-op pass-through. Tests that care can override.
    when(symbolicator.symbolicate(101L, "{}")).thenReturn("{}");
    fingerprint = new Fingerprint("abc123", "TypeError: x", null, Fingerprint.Level.ERROR);
    sampleResult =
        new EventStore.PersistResult(
            7L,
            true,
            "error",
            Instant.parse("2026-05-05T12:00:00Z"),
            Instant.parse("2026-05-05T12:00:00Z"),
            1L);
  }

  @Test
  void persistsAndPublishesPersistedEventOnFirstEncounter() {
    when(fingerprinter.compute(event.rawPayload())).thenReturn(fingerprint);
    when(store.persist(event, fingerprint, null)).thenReturn(sampleResult);

    Result result = service.process(event);

    assertThat(result).isEqualTo(new Result.Persisted(7L, true));
    ArgumentCaptor<PersistedEvent> captor = ArgumentCaptor.forClass(PersistedEvent.class);
    verify(publisher).publish(captor.capture());
    PersistedEvent published = captor.getValue();
    assertThat(published.issueId()).isEqualTo(7L);
    assertThat(published.projectId()).isEqualTo(101L);
    assertThat(published.level()).isEqualTo("error");
    assertThat(published.newIssue()).isTrue();
    assertThat(published.occurrenceCount()).isEqualTo(1L);
  }

  @Test
  void reportsExistingIssueOnRepeatEncounter() {
    when(fingerprinter.compute(event.rawPayload())).thenReturn(fingerprint);
    when(store.persist(event, fingerprint, null))
        .thenReturn(
            new EventStore.PersistResult(
                7L,
                false,
                "error",
                Instant.parse("2026-05-05T10:00:00Z"),
                Instant.parse("2026-05-05T12:00:00Z"),
                42L));

    Result result = service.process(event);

    assertThat(result).isEqualTo(new Result.Persisted(7L, false));
  }

  @Test
  void unknownFingerprintIsSalvagedNotDropped() {
    Fingerprint unknown =
        new Fingerprint("unknown", "Unparseable event", null, Fingerprint.Level.ERROR);
    when(fingerprinter.compute(event.rawPayload())).thenReturn(unknown);
    when(store.persist(event, unknown, null))
        .thenReturn(
            new EventStore.PersistResult(
                99L,
                true,
                "error",
                Instant.parse("2026-05-05T12:00:00Z"),
                Instant.parse("2026-05-05T12:00:00Z"),
                1L));

    Result result = service.process(event);

    assertThat(result).isEqualTo(new Result.SalvagedAsUnknown(99L));
    verify(publisher).publish(any()); // even unparseable events feed alerts
  }

  @Test
  void transientDbFailureMarksEventRetryableAndDoesNotPublish() {
    when(fingerprinter.compute(event.rawPayload())).thenReturn(fingerprint);
    when(store.persist(any(), any(), any()))
        .thenThrow(new TransientDataAccessResourceException("connection lost"));

    Result result = service.process(event);

    assertThat(result).isInstanceOf(Result.Retryable.class);
    assertThat(((Result.Retryable) result).reason()).contains("connection lost");
    verify(publisher, never()).publish(any());
  }

  @Test
  void symbolicatesBeforeFingerprintingAndPersists() {
    String enriched = "{\"release\":\"1.0.0\",\"originalFilename\":\"app.js\"}";
    when(symbolicator.symbolicate(101L, "{}")).thenReturn(enriched);
    when(fingerprinter.compute(enriched)).thenReturn(fingerprint);
    ArgumentCaptor<IncomingEvent> captor = ArgumentCaptor.forClass(IncomingEvent.class);
    when(store.persist(captor.capture(), any(), any())).thenReturn(sampleResult);

    Result result = service.process(event);

    assertThat(result).isEqualTo(new Result.Persisted(7L, true));
    assertThat(captor.getValue().rawPayload()).isEqualTo(enriched);
    assertThat(captor.getValue().eventId()).isEqualTo(event.eventId());
  }

  @Test
  void noChangeFromSymbolicatorReusesOriginalEvent() {
    when(fingerprinter.compute("{}")).thenReturn(fingerprint);
    ArgumentCaptor<IncomingEvent> captor = ArgumentCaptor.forClass(IncomingEvent.class);
    when(store.persist(captor.capture(), any(), any())).thenReturn(sampleResult);

    service.process(event);

    // Same instance — symbolicator returned the input unchanged so we skipped the rebuild.
    assertThat(captor.getValue()).isSameAs(event);
  }

  @Test
  void publisherFailureDoesNotFailThePersist() {
    // The persist already committed; an alerts-pipeline hiccup must not surface as Retryable
    // (which would re-deliver and double-count occurrence_count on the next run).
    when(fingerprinter.compute(event.rawPayload())).thenReturn(fingerprint);
    when(store.persist(event, fingerprint, null)).thenReturn(sampleResult);
    org.mockito.Mockito.doThrow(new RuntimeException("redis down")).when(publisher).publish(any());

    Result result = service.process(event);

    assertThat(result).isEqualTo(new Result.Persisted(7L, true));
  }
}
