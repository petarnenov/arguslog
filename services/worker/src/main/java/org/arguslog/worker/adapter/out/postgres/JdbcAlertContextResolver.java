package org.arguslog.worker.adapter.out.postgres;

import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.worker.application.port.AlertContextResolver;
import org.arguslog.worker.domain.PersistedEvent;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Single round-trip lookup: given an event, fetch the org slug, project slug and issue title in one
 * shot. The dispatcher needs all three to render an actionable message; we'd rather pay one join
 * than three queries per fired rule.
 */
@Component
public class JdbcAlertContextResolver implements AlertContextResolver {

  private static final String SELECT_SQL =
      """
      SELECT o.slug AS org_slug, p.slug AS project_slug, i.title AS issue_title
        FROM issues i
        JOIN projects p ON p.id = i.project_id
        JOIN organizations o ON o.id = p.org_id
       WHERE i.id = ? AND p.id = ?
      """;

  private final JdbcTemplate jdbc;

  public JdbcAlertContextResolver(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Optional<Resolved> resolve(PersistedEvent event) {
    try {
      Resolved row =
          jdbc.queryForObject(
              SELECT_SQL,
              (rs, rowNum) ->
                  new Resolved(
                      rs.getString("org_slug"),
                      rs.getString("project_slug"),
                      rs.getString("issue_title")),
              event.issueId(),
              event.projectId());
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }
}
