package org.arguslog.api.alerts.adapter.out.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.alerts.application.port.AlertRuleRepository;
import org.arguslog.api.alerts.domain.AlertRule;
import org.arguslog.api.security.OrgContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcAlertRuleRepository implements AlertRuleRepository {

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final RowMapper<AlertRule> rowMapper = this::mapRow;

  public JdbcAlertRuleRepository(DataSource dataSource, ObjectMapper json) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.json = json;
  }

  @Override
  public AlertRule create(
      long projectId,
      String name,
      JsonNode conditions,
      JsonNode actions,
      int throttleSeconds,
      boolean enabled) {
    pinOrgContextForRls();
    return jdbc.queryForObject(
        """
        INSERT INTO alert_rules (project_id, name, conditions, actions, throttle_seconds, enabled)
             VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?)
          RETURNING id, project_id, name, conditions::text, actions::text,
                    throttle_seconds, enabled, created_at
        """,
        new Object[] {
          projectId, name, serialize(conditions), serialize(actions), throttleSeconds, enabled
        },
        new int[] {
          Types.BIGINT, Types.VARCHAR, Types.OTHER, Types.OTHER, Types.INTEGER, Types.BOOLEAN,
        },
        rowMapper);
  }

  @Override
  public List<AlertRule> listForProject(long projectId) {
    pinOrgContextForRls();
    return jdbc.query(
        """
        SELECT id, project_id, name, conditions::text, actions::text,
               throttle_seconds, enabled, created_at
          FROM alert_rules
         WHERE project_id = ?
         ORDER BY created_at DESC, id DESC
        """,
        rowMapper,
        projectId);
  }

  @Override
  public Optional<AlertRule> find(long projectId, long id) {
    pinOrgContextForRls();
    try {
      return Optional.ofNullable(
          jdbc.queryForObject(
              """
              SELECT id, project_id, name, conditions::text, actions::text,
                     throttle_seconds, enabled, created_at
                FROM alert_rules
               WHERE project_id = ? AND id = ?
              """,
              rowMapper,
              projectId,
              id));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<AlertRule> update(
      long projectId,
      long id,
      String name,
      JsonNode conditions,
      JsonNode actions,
      int throttleSeconds,
      boolean enabled) {
    pinOrgContextForRls();
    int updated =
        jdbc.update(
            """
            UPDATE alert_rules
               SET name = ?, conditions = ?::jsonb, actions = ?::jsonb,
                   throttle_seconds = ?, enabled = ?
             WHERE project_id = ? AND id = ?
            """,
            name,
            serialize(conditions),
            serialize(actions),
            throttleSeconds,
            enabled,
            projectId,
            id);
    if (updated == 0) return Optional.empty();
    return find(projectId, id);
  }

  @Override
  public boolean delete(long projectId, long id) {
    pinOrgContextForRls();
    return jdbc.update("DELETE FROM alert_rules WHERE project_id = ? AND id = ?", projectId, id)
        > 0;
  }

  private void pinOrgContextForRls() {
    long orgId = OrgContext.requireCurrent();
    jdbc.queryForObject(
        "SELECT set_config('argus.org_id', ?, true)", String.class, String.valueOf(orgId));
  }

  private AlertRule mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new AlertRule(
        rs.getLong("id"),
        rs.getLong("project_id"),
        rs.getString("name"),
        parse(rs.getString("conditions")),
        parse(rs.getString("actions")),
        rs.getInt("throttle_seconds"),
        rs.getBoolean("enabled"),
        rs.getTimestamp("created_at").toInstant());
  }

  private String serialize(JsonNode node) {
    try {
      return json.writeValueAsString(node);
    } catch (Exception e) {
      throw new IllegalStateException("alert rule JSON serialize failed", e);
    }
  }

  private JsonNode parse(String raw) {
    try {
      return json.readTree(raw);
    } catch (IOException e) {
      throw new IllegalStateException("alert_rules row had invalid JSON: " + raw, e);
    }
  }
}
