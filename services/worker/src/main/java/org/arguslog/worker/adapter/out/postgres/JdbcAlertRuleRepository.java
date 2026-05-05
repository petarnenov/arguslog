package org.arguslog.worker.adapter.out.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import javax.sql.DataSource;
import org.arguslog.worker.application.port.AlertRuleRepository;
import org.arguslog.worker.domain.AlertRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAlertRuleRepository implements AlertRuleRepository {

  // Filtered to enabled rows so a quiescent rule doesn't pay the JSON parse cost on every event.
  // The partial index `idx_alert_rules_project ... WHERE enabled` from V1 covers the lookup.
  private static final String SELECT_ENABLED_FOR_PROJECT_SQL =
      """
      SELECT id, project_id, name, conditions::text AS conditions_json,
             actions::text AS actions_json, throttle_seconds
        FROM alert_rules
       WHERE project_id = ? AND enabled = TRUE
      """;

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcAlertRuleRepository(DataSource dataSource, ObjectMapper mapper) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.mapper = mapper;
  }

  @Override
  public List<AlertRule> enabledForProject(long projectId) {
    return jdbc.query(
        SELECT_ENABLED_FOR_PROJECT_SQL,
        (rs, rowNum) ->
            new AlertRule(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("name"),
                parse(rs.getString("conditions_json")),
                parse(rs.getString("actions_json")),
                rs.getInt("throttle_seconds")),
        projectId);
  }

  private JsonNode parse(String raw) {
    try {
      return mapper.readTree(raw);
    } catch (IOException e) {
      throw new IllegalStateException("alert_rules row had invalid JSON: " + raw, e);
    }
  }
}
