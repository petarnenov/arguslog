package org.arguslog.api.alerts.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.arguslog.api.alerts.domain.AlertRule;

public record AlertRuleResponse(
    long id,
    @JsonProperty("projectId") long projectId,
    String name,
    JsonNode conditions,
    JsonNode actions,
    @JsonProperty("throttleSeconds") int throttleSeconds,
    boolean enabled,
    @JsonProperty("createdAt") Instant createdAt) {

  public static AlertRuleResponse from(AlertRule r) {
    return new AlertRuleResponse(
        r.id(),
        r.projectId(),
        r.name(),
        r.conditions(),
        r.actions(),
        r.throttleSeconds(),
        r.enabled(),
        r.createdAt());
  }
}
