package org.arguslog.api.alerts.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.alerts.application.port.AlertRuleRepository;
import org.arguslog.api.alerts.application.port.AlertRuleWriteRepository;
import org.arguslog.api.alerts.domain.AlertRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertRuleService implements AlertRuleUseCase {

  // Bounds chosen so a misconfigured rule can't either spam the dispatcher (too short) or go
  // silent for hours (too long). Operators with extreme needs hit the api directly.
  static final int MIN_THROTTLE = 30;
  static final int MAX_THROTTLE = 86_400; // 24h
  static final int MAX_DESTINATIONS = 8;

  private static final List<String> KNOWN_LEVELS =
      List.of("fatal", "error", "warning", "info", "debug");

  private final AlertRuleRepository repository;
  private final AlertRuleWriteRepository writes;

  public AlertRuleService(AlertRuleRepository repository, AlertRuleWriteRepository writes) {
    this.repository = repository;
    this.writes = writes;
  }

  @Override
  @Transactional
  public AlertRule create(
      long projectId,
      String name,
      JsonNode conditions,
      JsonNode actions,
      int throttleSeconds,
      boolean enabled) {
    requireName(name);
    validateConditions(conditions);
    validateActions(actions);
    int throttle = clampThrottle(throttleSeconds);
    return writes.create(projectId, name.trim(), conditions, actions, throttle, enabled);
  }

  @Override
  @Transactional(readOnly = true)
  public List<AlertRule> list(long projectId) {
    return repository.listForProject(projectId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AlertRule> get(long projectId, long id) {
    return repository.find(projectId, id);
  }

  @Override
  @Transactional
  public Optional<AlertRule> update(
      long projectId,
      long id,
      String name,
      JsonNode conditions,
      JsonNode actions,
      int throttleSeconds,
      boolean enabled) {
    if (repository.find(projectId, id).isEmpty()) return Optional.empty();
    requireName(name);
    validateConditions(conditions);
    validateActions(actions);
    int throttle = clampThrottle(throttleSeconds);
    return writes.update(projectId, id, name.trim(), conditions, actions, throttle, enabled);
  }

  @Override
  @Transactional
  public boolean delete(long projectId, long id) {
    return writes.delete(projectId, id);
  }

  // ── validation ───────────────────────────────────────────────────────────

  private static void requireName(String name) {
    if (name == null || name.trim().isEmpty()) {
      throw new InvalidAlertRuleException("name is required");
    }
  }

  /**
   * Surface-level shape check. Deeper semantic evaluation happens in the worker — keeping it out of
   * the api means worker-only DSL extensions don't need an api PR every time.
   */
  static void validateConditions(JsonNode conditions) {
    if (conditions == null || !conditions.isObject()) {
      throw new InvalidAlertRuleException("conditions must be a JSON object (use {} for 'always')");
    }
    JsonNode level = conditions.get("level");
    if (level != null) {
      if (!level.isObject() || !level.path("in").isArray() || level.path("in").isEmpty()) {
        throw new InvalidAlertRuleException(
            "conditions.level must look like {\"in\":[\"error\",…]}");
      }
      for (JsonNode entry : level.path("in")) {
        if (!entry.isTextual() || !KNOWN_LEVELS.contains(entry.asText())) {
          throw new InvalidAlertRuleException(
              "conditions.level.in entries must be one of " + KNOWN_LEVELS);
        }
      }
    }
    JsonNode tag = conditions.get("tag");
    if (tag != null) {
      if (!tag.isObject() || !tag.path("key").isTextual() || tag.path("key").asText().isBlank()) {
        throw new InvalidAlertRuleException("conditions.tag.key must be a non-empty string");
      }
      if (!tag.path("in").isArray() || tag.path("in").isEmpty()) {
        throw new InvalidAlertRuleException("conditions.tag.in must be a non-empty array");
      }
    }
    JsonNode window = conditions.get("firstSeenWindow");
    if (window != null) {
      if (!window.isTextual()) {
        throw new InvalidAlertRuleException(
            "conditions.firstSeenWindow must be an ISO-8601 duration string (e.g. PT5M)");
      }
      try {
        Duration.parse(window.asText());
      } catch (RuntimeException e) {
        throw new InvalidAlertRuleException(
            "conditions.firstSeenWindow is not a valid ISO-8601 duration: " + window.asText());
      }
    }
    JsonNode threshold = conditions.get("occurrenceThreshold");
    if (threshold != null) {
      if (!threshold.canConvertToInt() || threshold.asInt() < 1) {
        throw new InvalidAlertRuleException(
            "conditions.occurrenceThreshold must be a positive integer");
      }
    }
  }

  static void validateActions(JsonNode actions) {
    if (actions == null || !actions.isObject()) {
      throw new InvalidAlertRuleException("actions must be a JSON object");
    }
    JsonNode dests = actions.get("destinationIds");
    if (dests == null || !dests.isArray() || dests.isEmpty()) {
      throw new InvalidAlertRuleException(
          "actions.destinationIds must be a non-empty array of destination ids");
    }
    if (dests.size() > MAX_DESTINATIONS) {
      throw new InvalidAlertRuleException(
          "actions.destinationIds capped at " + MAX_DESTINATIONS + " per rule");
    }
    for (JsonNode entry : dests) {
      if (!entry.canConvertToLong() || entry.asLong() <= 0) {
        throw new InvalidAlertRuleException(
            "actions.destinationIds entries must be positive integers");
      }
    }
  }

  static int clampThrottle(int requested) {
    if (requested < MIN_THROTTLE) return MIN_THROTTLE;
    if (requested > MAX_THROTTLE) return MAX_THROTTLE;
    return requested;
  }
}
