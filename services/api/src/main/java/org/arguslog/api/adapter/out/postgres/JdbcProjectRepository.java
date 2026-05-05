package org.arguslog.api.adapter.out.postgres;

import org.arguslog.api.application.port.ProjectRepository;
import java.util.OptionalLong;
import javax.sql.DataSource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcProjectRepository implements ProjectRepository {

  private final JdbcTemplate jdbc;

  public JdbcProjectRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public OptionalLong findOrgIdForProject(long projectId) {
    try {
      Long orgId =
          jdbc.queryForObject("SELECT org_id FROM projects WHERE id = ?", Long.class, projectId);
      return orgId == null ? OptionalLong.empty() : OptionalLong.of(orgId);
    } catch (EmptyResultDataAccessException e) {
      return OptionalLong.empty();
    }
  }
}
