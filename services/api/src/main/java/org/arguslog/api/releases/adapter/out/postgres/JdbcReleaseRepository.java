package org.arguslog.api.releases.adapter.out.postgres;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.domain.Release;
import org.arguslog.api.security.OrgContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcReleaseRepository implements ReleaseRepository {

  private final JdbcTemplate jdbc;
  private final RowMapper<Release> rowMapper = this::mapRow;

  public JdbcReleaseRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Release create(long projectId, String version) {
    pinOrgContextForRls();
    return jdbc.queryForObject(
        """
        INSERT INTO releases (project_id, version)
             VALUES (?, ?)
          RETURNING id, project_id, version, created_at
        """,
        new Object[] {projectId, version},
        new int[] {Types.BIGINT, Types.VARCHAR},
        rowMapper);
  }

  @Override
  public List<Release> listForProject(long projectId) {
    pinOrgContextForRls();
    return jdbc.query(
        """
        SELECT id, project_id, version, created_at
          FROM releases
         WHERE project_id = ?
         ORDER BY created_at DESC, id DESC
        """,
        rowMapper,
        projectId);
  }

  @Override
  public Optional<Release> find(long projectId, long id) {
    pinOrgContextForRls();
    try {
      Release row =
          jdbc.queryForObject(
              """
              SELECT id, project_id, version, created_at
                FROM releases
               WHERE project_id = ? AND id = ?
              """,
              rowMapper,
              projectId,
              id);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<Release> findByVersion(long projectId, String version) {
    pinOrgContextForRls();
    try {
      Release row =
          jdbc.queryForObject(
              """
              SELECT id, project_id, version, created_at
                FROM releases
               WHERE project_id = ? AND version = ?
              """,
              rowMapper,
              projectId,
              version);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<Release> updateVersion(long projectId, long id, String newVersion) {
    pinOrgContextForRls();
    try {
      Release row =
          jdbc.queryForObject(
              """
              UPDATE releases
                 SET version = ?
               WHERE project_id = ? AND id = ?
              RETURNING id, project_id, version, created_at
              """,
              new Object[] {newVersion, projectId, id},
              new int[] {Types.VARCHAR, Types.BIGINT, Types.BIGINT},
              rowMapper);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public boolean delete(long projectId, long id) {
    pinOrgContextForRls();
    return jdbc.update("DELETE FROM releases WHERE project_id = ? AND id = ?", projectId, id) > 0;
  }

  private void pinOrgContextForRls() {
    long orgId = OrgContext.requireCurrent();
    jdbc.queryForObject(
        "SELECT set_config('arguslog.org_id', ?, true)", String.class, String.valueOf(orgId));
  }

  private Release mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new Release(
        rs.getLong("id"),
        rs.getLong("project_id"),
        rs.getString("version"),
        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());
  }
}
