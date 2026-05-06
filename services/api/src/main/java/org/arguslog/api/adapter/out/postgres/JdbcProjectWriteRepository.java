package org.arguslog.api.adapter.out.postgres;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.domain.Project;
import org.arguslog.api.security.OrgContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

@Component
public class JdbcProjectWriteRepository implements ProjectWriteRepository {

  private static final int MAX_SLUG_ATTEMPTS = 50;

  private final JdbcTemplate jdbc;

  public JdbcProjectWriteRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Project create(long orgId, String baseSlug, String name, String platform) {
    pinOrg();
    for (int attempt = 1; attempt <= MAX_SLUG_ATTEMPTS; attempt++) {
      String slug = attempt == 1 ? baseSlug : baseSlug + "-" + attempt;
      try {
        return insert(orgId, slug, name, platform);
      } catch (DataIntegrityViolationException e) {
        // unique violation on (org_id, slug) — try the next suffix
      }
    }
    throw new IllegalStateException(
        "could not allocate a unique project slug after "
            + MAX_SLUG_ATTEMPTS
            + " attempts for org "
            + orgId);
  }

  private Project insert(long orgId, String slug, String name, String platform) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var ps =
              connection.prepareStatement(
                  "INSERT INTO projects (org_id, slug, name, platform) VALUES (?, ?, ?, ?) "
                      + "RETURNING id, created_at",
                  new String[] {"id", "created_at"});
          ps.setLong(1, orgId);
          ps.setString(2, slug);
          ps.setString(3, name);
          ps.setString(4, platform);
          return ps;
        },
        keys);
    var row = keys.getKeys();
    if (row == null) {
      throw new IllegalStateException("INSERT returned no keys");
    }
    long id = ((Number) row.get("id")).longValue();
    Timestamp createdAt = (Timestamp) row.get("created_at");
    return new Project(id, orgId, slug, name, platform, createdAt.toInstant());
  }

  @Override
  public List<Project> listForOrg(long orgId) {
    pinOrg();
    return jdbc.query(
        """
            SELECT id, org_id, slug, name, platform, created_at
              FROM projects
             WHERE org_id = ?
             ORDER BY slug ASC
            """,
        (rs, rowNum) ->
            new Project(
                rs.getLong("id"),
                rs.getLong("org_id"),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("platform"),
                rs.getTimestamp("created_at").toInstant()),
        orgId);
  }

  @Override
  public Optional<Project> find(long orgId, long projectId) {
    pinOrg();
    try {
      Project project =
          jdbc.queryForObject(
              """
              SELECT id, org_id, slug, name, platform, created_at
                FROM projects
               WHERE org_id = ? AND id = ?
              """,
              (rs, rowNum) ->
                  new Project(
                      rs.getLong("id"),
                      rs.getLong("org_id"),
                      rs.getString("slug"),
                      rs.getString("name"),
                      rs.getString("platform"),
                      rs.getTimestamp("created_at").toInstant()),
              orgId,
              projectId);
      return Optional.ofNullable(project);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private void pinOrg() {
    long orgId = OrgContext.requireCurrent();
    jdbc.queryForObject(
        "SELECT set_config('arguslog.org_id', ?, true)", String.class, String.valueOf(orgId));
  }
}
