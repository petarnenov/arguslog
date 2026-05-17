package org.arguslog.api.alerts.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Typed shape of the alert rule conditions JSON. Mirrors the DSL the worker actually evaluates (see
 * {@code RuleEvaluator}): {@code level.in}, {@code firstSeenWindow}, {@code occurrenceThreshold},
 * {@code tag.{key,in}}. All clauses optional; an empty conditions object means "always match".
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown=true)} is load-bearing — older JSONB rows may carry
 * unknown clauses the api hasn't taught itself yet (forward-compat is intentional, the worker
 * over-matches until it catches up). Dropping unknown fields on deserialize keeps GET working
 * through those upgrades.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertRuleConditions(
    LevelClause level, String firstSeenWindow, Integer occurrenceThreshold, TagClause tag) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LevelClause(List<String> in) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TagClause(String key, List<String> in) {}
}
