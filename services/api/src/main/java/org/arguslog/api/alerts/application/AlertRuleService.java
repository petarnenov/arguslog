package org.arguslog.api.alerts.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.alerts.adapter.in.web.dto.AlertRuleActions;
import org.arguslog.api.alerts.adapter.in.web.dto.AlertRuleConditions;
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
  private final ObjectMapper mapper;

  public AlertRuleService(
      AlertRuleRepository repository, AlertRuleWriteRepository writes, ObjectMapper mapper) {
    this.repository = repository;
    this.writes = writes;
    this.mapper = mapper;
  }

  @Override
  @Transactional
  public AlertRule create(
      long projectId,
      String name,
      AlertRuleConditions conditions,
      AlertRuleActions actions,
      int throttleSeconds,
      boolean enabled) {
    requireName(name);
    validateConditions(conditions);
    validateActions(actions);
    int throttle = clampThrottle(throttleSeconds);
    return writes.create(
        projectId, name.trim(), toJson(conditions), toJson(actions), throttle, enabled);
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
      AlertRuleConditions conditions,
      AlertRuleActions actions,
      int throttleSeconds,
      boolean enabled) {
    if (repository.find(projectId, id).isEmpty()) return Optional.empty();
    requireName(name);
    validateConditions(conditions);
    validateActions(actions);
    int throttle = clampThrottle(throttleSeconds);
    return writes.update(
        projectId, id, name.trim(), toJson(conditions), toJson(actions), throttle, enabled);
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
   * Surface-level shape check on the typed record. Deeper semantic evaluation happens in the
   * worker — keeping it out of the api means worker-only DSL extensions don't need an api PR
   * every time.
   */
  static void validateConditions(AlertRuleConditions conditions) {
    if (conditions == null) {
      throw new InvalidAlertRuleException("conditions are required (use {} for 'always')");
    }
    if (conditions.level() != null) {
      List<String> in = conditions.level().in();
      if (in == null || in.isEmpty()) {
        throw new InvalidAlertRuleException(
            "conditions.level must look like {\"in\":[\"error\",…]}");
      }
      for (String entry : in) {
        if (entry == null || !KNOWN_LEVELS.contains(entry)) {
          throw new InvalidAlertRuleException(
              "conditions.level.in entries must be one of " + KNOWN_LEVELS);
        }
      }
    }
    if (conditions.tag() != null) {
      String key = conditions.tag().key();
      if (key == null || key.isBlank()) {
        throw new InvalidAlertRuleException("conditions.tag.key must be a non-empty string");
      }
      List<String> in = conditions.tag().in();
      if (in == null || in.isEmpty()) {
        throw new InvalidAlertRuleException("conditions.tag.in must be a non-empty array");
      }
      for (String entry : in) {
        if (entry == null || entry.isBlank()) {
          throw new InvalidAlertRuleException(
              "conditions.tag.in entries must be non-empty strings");
        }
      }
    }
    String window = conditions.firstSeenWindow();
    if (window != null) {
      try {
        Duration.parse(window);
      } catch (RuntimeException e) {
        throw new InvalidAlertRuleException(
            "conditions.firstSeenWindow is not a valid ISO-8601 duration: " + window);
      }
    }
    Integer threshold = conditions.occurrenceThreshold();
    if (threshold != null && threshold < 1) {
      throw new InvalidAlertRuleException(
          "conditions.occurrenceThreshold must be a positive integer");
    }
  }

  static void validateActions(AlertRuleActions actions) {
    if (actions == null) {
      throw new InvalidAlertRuleException("actions are required");
    }
    List<Long> dests = actions.destinationIds();
    if (dests == null || dests.isEmpty()) {
      throw new InvalidAlertRuleException(
          "actions.destinationIds must be a non-empty array of destination ids");
    }
    if (dests.size() > MAX_DESTINATIONS) {
      throw new InvalidAlertRuleException(
          "actions.destinationIds capped at " + MAX_DESTINATIONS + " per rule");
    }
    for (Long entry : dests) {
      if (entry == null || entry <= 0) {
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

  private JsonNode toJson(Object value) {
    try {
      // Round-trip through tree → bytes → tree to honor @JsonInclude(NON_NULL) on the records.
      return mapper.readTree(mapper.writeValueAsBytes(value));
    } catch (java.io.IOException e) {
      // The typed records are always serializable; this should not happen short of a JVM bug.
      throw new IllegalStateException("re-serialize alert rule payload failed", e);
    }
  }
}
