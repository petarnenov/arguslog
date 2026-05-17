package org.arguslog.api.alerts.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.arguslog.api.alerts.domain.AlertRule;

public record AlertRuleResponse(
    long id,
    @JsonProperty("projectId") long projectId,
    String name,
    AlertRuleConditions conditions,
    AlertRuleActions actions,
    @JsonProperty("throttleSeconds") int throttleSeconds,
    boolean enabled,
    @JsonProperty("createdAt") Instant createdAt) {

  /**
   * Converts the domain row (which keeps {@code JsonNode} for forward-compat — the worker reads the
   * same shape) into the typed wire DTO. {@code @JsonIgnoreProperties(ignoreUnknown=true)} on the
   * records means rows carrying clauses the api hasn't typed yet still deserialize cleanly.
   */
  public static AlertRuleResponse from(AlertRule r, ObjectMapper mapper) {
    return new AlertRuleResponse(
        r.id(),
        r.projectId(),
        r.name(),
        toConditions(r.conditions(), mapper),
        toActions(r.actions(), mapper),
        r.throttleSeconds(),
        r.enabled(),
        r.createdAt());
  }

  private static AlertRuleConditions toConditions(JsonNode raw, ObjectMapper mapper) {
    if (raw == null || raw.isNull()) {
      return new AlertRuleConditions(null, null, null, null);
    }
    try {
      return mapper.treeToValue(raw, AlertRuleConditions.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Domain stores were validated on the way in; falling back to an empty record here keeps
      // GET 200-able even if a hand-edited DB row carries a malformed shape — the operator can
      // PUT the right one to fix it.
      return new AlertRuleConditions(null, null, null, null);
    }
  }

  private static AlertRuleActions toActions(JsonNode raw, ObjectMapper mapper) {
    if (raw == null || raw.isNull()) {
      return new AlertRuleActions(java.util.List.of());
    }
    try {
      return mapper.treeToValue(raw, AlertRuleActions.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return new AlertRuleActions(java.util.List.of());
    }
  }
}
