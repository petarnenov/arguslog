package org.arguslog.worker.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.arguslog.worker.application.port.EventStore;
import org.arguslog.worker.application.port.Fingerprinter;
import org.arguslog.worker.application.port.PersistedEventPublisher;
import org.arguslog.worker.application.port.Symbolicator;
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
  private final Symbolicator symbolicator;
  private final ObjectMapper mapper;

  public ProcessEventService(
      Fingerprinter fingerprinter,
      EventStore store,
      PersistedEventPublisher persistedEventPublisher,
      Symbolicator symbolicator,
      ObjectMapper mapper) {
    this.fingerprinter = fingerprinter;
    this.store = store;
    this.persistedEventPublisher = persistedEventPublisher;
    this.symbolicator = symbolicator;
    this.mapper = mapper;
  }

  @Override
  public Result process(IncomingEvent event) {
    // Symbolicate BEFORE fingerprint so the issue group is keyed off the original frame —
    // otherwise re-deploys would create new issues for the same logical bug whenever the bundler
    // renames a chunk. Symbolicator returns the input unchanged on any failure (best-effort).
    String enrichedPayload = symbolicator.symbolicate(event.projectId(), event.rawPayload());
    IncomingEvent enriched =
        enrichedPayload.equals(event.rawPayload()) ? event : withPayload(event, enrichedPayload);

    Fingerprint fingerprint = fingerprinter.compute(enriched.rawPayload());
    String releaseVersion = extractReleaseVersion(enriched.rawPayload());
    Map<String, String> tags = extractTags(enriched.rawPayload());
    try {
      EventStore.PersistResult result = store.persist(enriched, fingerprint, releaseVersion);
      publishPersisted(enriched, result, tags);
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
   * Pulls {@code payload.release} (the SDK convention shared with CachingSymbolicator) as a plain
   * string. Returns {@code null} on missing field, non-string field, or unparseable JSON — the
   * persist path tolerates a null release version (column lands NULL).
   */
  private String extractReleaseVersion(String payload) {
    try {
      JsonNode root = mapper.readTree(payload);
      JsonNode release = root.path("release");
      if (release.isTextual()) {
        String text = release.asText().trim();
        return text.isEmpty() ? null : text;
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  private static IncomingEvent withPayload(IncomingEvent original, String payload) {
    return new IncomingEvent(
        original.eventId(),
        original.projectId(),
        original.dsnPublicKey(),
        original.receivedAt(),
        payload,
        original.clientIp(),
        original.userAgent());
  }

  /**
   * Pulls SDK tags off the payload as a flat {@code Map<String,String>}. Supports both Sentry-
   * style shapes — top-level object ({@code "tags":{"env":"prod"}}) and array-of-pairs ({@code
   * "tags":[["env","prod"]]}). Non-textual values are stringified; non-textual keys dropped.
   * Returns an empty map on missing field or malformed JSON — tag-clause rules then simply don't
   * fire for this event.
   */
  private Map<String, String> extractTags(String payload) {
    try {
      JsonNode root = mapper.readTree(payload);
      JsonNode tags = root.path("tags");
      Map<String, String> out = new LinkedHashMap<>();
      if (tags.isObject()) {
        tags.fields()
            .forEachRemaining(
                e -> {
                  if (!e.getValue().isNull()) out.put(e.getKey(), e.getValue().asText());
                });
      } else if (tags.isArray()) {
        for (JsonNode pair : tags) {
          if (pair.isArray() && pair.size() == 2 && pair.get(0).isTextual()) {
            out.put(pair.get(0).asText(), pair.get(1).asText());
          }
        }
      }
      return out;
    } catch (Exception e) {
      return Map.of();
    }
  }

  /**
   * Best-effort hand-off to the alerts pipeline. Failure here MUST NOT fail the event persistence —
   * Redis being briefly unreachable would otherwise look like a persistence failure and the caller
   * would re-deliver, double-counting occurrence_count.
   */
  private void publishPersisted(
      IncomingEvent event, EventStore.PersistResult result, Map<String, String> tags) {
    try {
      persistedEventPublisher.publish(
          new PersistedEvent(
              result.issueId(),
              event.projectId(),
              result.level(),
              result.newIssue(),
              result.occurrenceCount(),
              result.firstSeenAt(),
              result.lastSeenAt(),
              tags));
    } catch (RuntimeException e) {
      log.warn(
          "failed to publish persisted-event for issue {}; alerts may miss this occurrence: {}",
          result.issueId(),
          e.getMessage());
    }
  }
}
