package org.arguslog.api.alerts.adapter.in.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record AlertRuleRequest(
    String name, JsonNode conditions, JsonNode actions, Integer throttleSeconds, Boolean enabled) {

  public int throttleOrDefault() {
    return throttleSeconds == null ? 300 : throttleSeconds;
  }

  public boolean enabledOrDefault() {
    return enabled == null || enabled;
  }
}
