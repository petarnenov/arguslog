package org.arguslog.api.alerts.adapter.in.web.dto;

/**
 * Wire format for create + update. {@code conditions} and {@code actions} are typed (no longer
 * opaque {@code JsonNode}) so OpenAPI emits a real schema and MCP / CLI clients can build
 * payloads without guessing field names.
 */
public record AlertRuleRequest(
    String name,
    AlertRuleConditions conditions,
    AlertRuleActions actions,
    Integer throttleSeconds,
    Boolean enabled) {

  public int throttleOrDefault() {
    return throttleSeconds == null ? 300 : throttleSeconds;
  }

  public boolean enabledOrDefault() {
    return enabled == null || enabled;
  }
}
