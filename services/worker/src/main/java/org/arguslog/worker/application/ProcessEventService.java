package org.arguslog.worker.application;

import org.arguslog.worker.application.port.EventStore;
import org.arguslog.worker.application.port.Fingerprinter;
import org.arguslog.worker.application.port.PersistedEventPublisher;
import org.arguslog.worker.domain.Fingerprint;
import org.arguslog.worker.domain.IncomingEvent;
import org.arguslog.worker.domain.PersistedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class ProcessEventService implements ProcessEventUseCase {

  private static final Logger log = LoggerFactory.getLogger(ProcessEventService.class);

  private final Fingerprinter fingerprinter;
  private final EventStore store;
  private final PersistedEventPublisher persistedEventPublisher;

  public ProcessEventService(
      Fingerprinter fingerprinter,
      EventStore store,
      PersistedEventPublisher persistedEventPublisher) {
    this.fingerprinter = fingerprinter;
    this.store = store;
    this.persistedEventPublisher = persistedEventPublisher;
  }

  @Override
  public Result process(IncomingEvent event) {
    Fingerprint fingerprint = fingerprinter.compute(event.rawPayload());
    try {
      EventStore.PersistResult result = store.persist(event, fingerprint);
      publishPersisted(event, result);
      if ("unknown".equals(fingerprint.hash())) {
        return new Result.SalvagedAsUnknown(result.issueId());
      }
      return new Result.Persisted(result.issueId(), result.newIssue());
    } catch (DataAccessException e) {
      // Redis will redeliver after the visibility timeout — surface so the caller skips ACK.
      return new Result.Retryable(
          e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  /**
   * Best-effort hand-off to the alerts pipeline. Failure here MUST NOT fail the event persistence —
   * Redis being briefly unreachable would otherwise look like a persistence failure and the caller
   * would re-deliver, double-counting occurrence_count.
   */
  private void publishPersisted(IncomingEvent event, EventStore.PersistResult result) {
    try {
      persistedEventPublisher.publish(
          new PersistedEvent(
              result.issueId(),
              event.projectId(),
              result.level(),
              result.newIssue(),
              result.occurrenceCount(),
              result.firstSeenAt(),
              result.lastSeenAt()));
    } catch (RuntimeException e) {
      log.warn(
          "failed to publish persisted-event for issue {}; alerts may miss this occurrence: {}",
          result.issueId(),
          e.getMessage());
    }
  }
}
