package org.arguslog.worker.application;

import org.arguslog.worker.application.port.EventStore;
import org.arguslog.worker.application.port.Fingerprinter;
import org.arguslog.worker.domain.Fingerprint;
import org.arguslog.worker.domain.IncomingEvent;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class ProcessEventService implements ProcessEventUseCase {

  private final Fingerprinter fingerprinter;
  private final EventStore store;

  public ProcessEventService(Fingerprinter fingerprinter, EventStore store) {
    this.fingerprinter = fingerprinter;
    this.store = store;
  }

  @Override
  public Result process(IncomingEvent event) {
    Fingerprint fingerprint = fingerprinter.compute(event.rawPayload());
    try {
      EventStore.PersistResult result = store.persist(event, fingerprint);
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
}
