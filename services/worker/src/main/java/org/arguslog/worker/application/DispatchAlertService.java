package org.arguslog.worker.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.arguslog.worker.application.port.AlertContextResolver;
import org.arguslog.worker.application.port.AlertContextResolver.Resolved;
import org.arguslog.worker.application.port.AlertDestinationRepository;
import org.arguslog.worker.application.port.AlertDispatcher;
import org.arguslog.worker.domain.Alert;
import org.arguslog.worker.domain.AlertDestination;
import org.arguslog.worker.domain.AlertRule;
import org.arguslog.worker.domain.PersistedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Picks the right {@link AlertDispatcher} for each destination on a fired rule. Per-dispatcher
 * failures are isolated: a broken Telegram dispatch never drops a Slack one in the same fan-out.
 */
@Service
public class DispatchAlertService implements DispatchAlertUseCase {

  private static final Logger log = LoggerFactory.getLogger(DispatchAlertService.class);

  private final AlertDestinationRepository destinations;
  private final AlertContextResolver context;
  private final Map<AlertDestination.Kind, AlertDispatcher> dispatchers;

  public DispatchAlertService(
      AlertDestinationRepository destinations,
      AlertContextResolver context,
      List<AlertDispatcher> dispatchers) {
    this.destinations = destinations;
    this.context = context;
    this.dispatchers = new EnumMap<>(AlertDestination.Kind.class);
    for (AlertDispatcher d : dispatchers) {
      AlertDispatcher prior = this.dispatchers.put(d.kind(), d);
      if (prior != null) {
        throw new IllegalStateException(
            "two dispatchers registered for " + d.kind() + ": " + prior + " and " + d);
      }
    }
  }

  @Override
  public int dispatch(AlertRule rule, PersistedEvent event) {
    List<Long> destinationIds = readDestinationIds(rule.actions());
    if (destinationIds.isEmpty()) {
      log.debug("rule {} has no destinations; nothing to dispatch", rule.id());
      return 0;
    }

    Optional<Resolved> ctx = context.resolve(event);
    if (ctx.isEmpty()) {
      log.warn(
          "skip dispatch for rule {} — could not resolve project/issue context for issue {}",
          rule.id(),
          event.issueId());
      return 0;
    }

    List<AlertDestination> resolved = destinations.findAllById(destinationIds);
    if (resolved.isEmpty()) {
      log.warn(
          "rule {} pointed at destinations {} but none resolved (deleted? wrong org?)",
          rule.id(),
          destinationIds);
      return 0;
    }

    Alert alert = buildAlert(rule, event, ctx.get());
    int attempted = 0;
    for (AlertDestination destination : resolved) {
      AlertDispatcher dispatcher = dispatchers.get(destination.kind());
      if (dispatcher == null) {
        log.warn(
            "no dispatcher for kind {} (destination {}); skipping",
            destination.kind(),
            destination.id());
        continue;
      }
      attempted++;
      try {
        dispatcher.dispatch(alert, destination);
      } catch (RuntimeException e) {
        // Defense in depth: dispatchers are supposed to swallow their own failures, but a bug
        // there shouldn't poison the rest of the fan-out for the same alert.
        log.warn(
            "dispatcher {} threw for destination {} ({}): {}",
            dispatcher.getClass().getSimpleName(),
            destination.id(),
            destination.kind(),
            e.getMessage());
      }
    }
    return attempted;
  }

  private static List<Long> readDestinationIds(JsonNode actions) {
    if (actions == null || actions.isNull()) return List.of();
    JsonNode ids = actions.path("destinationIds");
    if (!ids.isArray()) return List.of();
    List<Long> out = new ArrayList<>(ids.size());
    for (JsonNode id : ids) {
      if (id.canConvertToLong()) out.add(id.asLong());
    }
    return out;
  }

  private static Alert buildAlert(AlertRule rule, PersistedEvent event, Resolved ctx) {
    return new Alert(
        rule.id(),
        rule.name(),
        event.projectId(),
        ctx.projectSlug(),
        ctx.orgSlug(),
        event.issueId(),
        ctx.issueTitle(),
        event.level(),
        event.occurrenceCount(),
        event.firstSeenAt(),
        event.lastSeenAt());
  }
}
