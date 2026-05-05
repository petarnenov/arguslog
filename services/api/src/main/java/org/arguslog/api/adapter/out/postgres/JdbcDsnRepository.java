package org.arguslog.api.adapter.out.postgres;

import java.sql.Timestamp;
import java.util.List;
import javax.sql.DataSource;
import org.arguslog.api.application.port.DsnRepository;
import org.arguslog.api.domain.Dsn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

@Component
public class JdbcDsnRepository implements DsnRepository {

  private final JdbcTemplate jdbc;

  public JdbcDsnRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Dsn create(long projectId, String dsnPublic) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var ps =
              connection.prepareStatement(
                  "INSERT INTO project_keys (project_id, dsn_public) VALUES (?, ?) "
                      + "RETURNING id, active, created_at",
                  new String[] {"id", "active", "created_at"});
          ps.setLong(1, projectId);
          ps.setString(2, dsnPublic);
          return ps;
        },
        keys);
    var row = keys.getKeys();
    if (row == null) {
      throw new IllegalStateException("INSERT returned no keys");
    }
    long id = ((Number) row.get("id")).longValue();
    boolean active = (Boolean) row.get("active");
    Timestamp createdAt = (Timestamp) row.get("created_at");
    return new Dsn(id, projectId, dsnPublic, active, createdAt.toInstant());
  }

  @Override
  public List<Dsn> listForProject(long projectId) {
    return jdbc.query(
        """
        SELECT id, project_id, dsn_public, active, created_at
          FROM project_keys
         WHERE project_id = ?
         ORDER BY created_at DESC, id DESC
        """,
        (rs, rowNum) ->
            new Dsn(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("dsn_public"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant()),
        projectId);
  }
}
