package org.arguslog.api.adapter.out.postgres;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.application.port.DsnRepository;
import org.arguslog.api.application.port.DsnWriteRepository;
import org.arguslog.api.domain.Dsn;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

@Component
public class JdbcDsnRepository implements DsnRepository, DsnWriteRepository {

  private static final RowMapper<Dsn> ROW_MAPPER =
      (rs, rowNum) ->
          new Dsn(
              rs.getLong("id"),
              rs.getLong("project_id"),
              rs.getString("dsn_public"),
              rs.getBoolean("active"),
              rs.getTimestamp("created_at").toInstant());

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
           AND active = TRUE
         ORDER BY created_at DESC, id DESC
        """,
        ROW_MAPPER,
        projectId);
  }

  @Override
  public Optional<Dsn> findByProjectAndId(long projectId, long keyId) {
    try {
      Dsn row =
          jdbc.queryForObject(
              """
              SELECT id, project_id, dsn_public, active, created_at
                FROM project_keys
               WHERE project_id = ?
                 AND id = ?
              """,
              ROW_MAPPER,
              projectId,
              keyId);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<Dsn> deactivate(long keyId) {
    return jdbc
        .query(
            """
            UPDATE project_keys
               SET active = FALSE
             WHERE id = ?
               AND active = TRUE
             RETURNING id, project_id, dsn_public, active, created_at
            """,
            ROW_MAPPER,
            keyId)
        .stream()
        .findFirst();
  }
}
