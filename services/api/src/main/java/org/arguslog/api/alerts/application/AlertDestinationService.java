package org.arguslog.api.alerts.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.alerts.application.port.AlertDestinationRepository;
import org.arguslog.api.alerts.application.port.AlertDestinationWriteRepository;
import org.arguslog.api.alerts.domain.AlertDestination;
import org.arguslog.api.alerts.domain.DestinationKind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertDestinationService implements AlertDestinationUseCase {

  private final AlertDestinationRepository repository;
  private final AlertDestinationWriteRepository writes;
  private final ObjectMapper json;

  public AlertDestinationService(
      AlertDestinationRepository repository,
      AlertDestinationWriteRepository writes,
      ObjectMapper json) {
    this.repository = repository;
    this.writes = writes;
    this.json = json;
  }

  @Override
  @Transactional
  public AlertDestination create(long orgId, DestinationKind kind, String name, JsonNode config) {
    validateConfig(kind, config);
    requireName(name);
    return writes.create(orgId, kind, name.trim(), serialize(config));
  }

  @Override
  @Transactional(readOnly = true)
  public List<AlertDestination> list(long orgId) {
    return repository.listForOrg(orgId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AlertDestination> get(long orgId, long id) {
    return repository.find(orgId, id);
  }

  @Override
  @Transactional
  public Optional<AlertDestination> update(long orgId, long id, String name, JsonNode config) {
    Optional<AlertDestination> existing = repository.find(orgId, id);
    if (existing.isEmpty()) return Optional.empty();
    requireName(name);
    // Per-field structured editing posture: a caller updating the name alone shouldn't have to
    // re-supply the secret config blob (the dashboard never shows it back, so the user would
    // be forced into a copy-paste-from-1Password ritual just to rename). Null/missing config
    // means "keep what's there"; an object means "replace and revalidate".
    String configJson;
    if (config == null || config.isNull()) {
      configJson = existing.get().configJson();
    } else {
      validateConfig(existing.get().kind(), config);
      configJson = serialize(config);
    }
    return writes.update(orgId, id, name.trim(), configJson);
  }

  @Override
  @Transactional
  public Optional<AlertDestination> setEnabled(long orgId, long id, boolean enabled) {
    return writes.setEnabled(orgId, id, enabled);
  }

  @Override
  @Transactional
  public boolean delete(long orgId, long id) {
    return writes.delete(orgId, id);
  }

  private void validateConfig(DestinationKind kind, JsonNode config) {
    if (config == null || !config.isObject()) {
      throw new InvalidDestinationConfigException("config must be a JSON object");
    }
    switch (kind) {
      case TELEGRAM -> {
        requireString(config, "chatId", kind);
        requireString(config, "botToken", kind);
      }
      case EMAIL -> {
        if (!config.path("to").isArray() || config.path("to").isEmpty()) {
          throw new InvalidDestinationConfigException(
              kind.dbValue() + ".to must be a non-empty array of recipient emails");
        }
      }
      case SLACK -> requireString(config, "webhookUrl", kind);
      case WEBHOOK -> requireString(config, "url", kind);
      case GITHUB_ISSUE -> {
        requireString(config, "owner", kind);
        requireString(config, "repo", kind);
        requireString(config, "token", kind);
        // `assignee` and `labels` are optional. The worker dispatcher applies sensible defaults
        // (`copilot-swe-agent` + `["arguslog-auto-triage"]`) when they're missing. If the
        // operator DID set them, sanity-check the shape so we catch typos at create time, not
        // at dispatch time.
        JsonNode assignee = config.get("assignee");
        if (assignee != null && !assignee.isNull()) {
          if (!assignee.isTextual() || assignee.asText().isBlank()) {
            throw new InvalidDestinationConfigException(
                kind.dbValue() + ".assignee must be a non-empty string when provided");
          }
        }
        JsonNode labels = config.get("labels");
        if (labels != null && !labels.isNull() && !labels.isArray()) {
          throw new InvalidDestinationConfigException(
              kind.dbValue() + ".labels must be an array of strings when provided");
        }
      }
    }
  }

  private static void requireString(JsonNode config, String field, DestinationKind kind) {
    JsonNode node = config.get(field);
    if (node == null || !node.isTextual() || node.asText().isBlank()) {
      throw new InvalidDestinationConfigException(
          kind.dbValue() + " destination requires a non-empty string '" + field + "'");
    }
  }

  private static void requireName(String name) {
    if (name == null || name.trim().isEmpty()) {
      throw new InvalidDestinationConfigException("name is required");
    }
  }

  private String serialize(JsonNode config) {
    try {
      return json.writeValueAsString(config);
    } catch (Exception e) {
      throw new IllegalStateException("re-serialize destination config failed", e);
    }
  }
}
