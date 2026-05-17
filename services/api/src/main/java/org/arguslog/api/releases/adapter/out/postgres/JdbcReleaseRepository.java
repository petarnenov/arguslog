package org.arguslog.api.releases.adapter.out.postgres;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.domain.Release;
import org.arguslog.api.releases.domain.ReleaseInput;
import org.arguslog.api.security.OrgContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcReleaseRepository implements ReleaseRepository {

  // Column order shared by every SELECT — kept short so the SQL is readable. The mapper assumes
  // this exact list; if you add a new column, append to BOTH the SELECT list and `mapRow`.
  private static final String SELECT_COLUMNS =
      "id, project_id, version, created_at, released_at, git_sha, git_ref, deploy_stage,"
          + " changelog";

  private final JdbcTemplate jdbc;
  private final RowMapper<Release> rowMapper = this::mapRow;

  public JdbcReleaseRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Release create(long projectId, ReleaseInput input) {
    pinOrgContextForRls();
    return jdbc.queryForObject(
        "INSERT INTO releases (project_id, version, released_at, git_sha, git_ref,"
            + " deploy_stage, changelog) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING "
            + SELECT_COLUMNS,
        new Object[] {
          projectId,
          input.version(),
          input.releasedAt() == null
              ? null
              : OffsetDateTime.ofInstant(input.releasedAt(), ZoneOffset.UTC),
          input.gitSha(),
          input.gitRef(),
          input.deployStage(),
          input.changelog()
        },
        new int[] {
          Types.BIGINT,
          Types.VARCHAR,
          Types.TIMESTAMP_WITH_TIMEZONE,
          Types.VARCHAR,
          Types.VARCHAR,
          Types.VARCHAR,
          Types.VARCHAR
        },
        rowMapper);
  }

  @Override
  public List<Release> listForProject(long projectId) {
    pinOrgContextForRls();
    return jdbc.query(
        "SELECT "
            + SELECT_COLUMNS
            + " FROM releases WHERE project_id = ? ORDER BY created_at DESC, id DESC",
        rowMapper,
        projectId);
  }

  @Override
  public Optional<Release> find(long projectId, long id) {
    pinOrgContextForRls();
    try {
      Release row =
          jdbc.queryForObject(
              "SELECT " + SELECT_COLUMNS + " FROM releases WHERE project_id = ? AND id = ?",
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
              "SELECT " + SELECT_COLUMNS + " FROM releases WHERE project_id = ? AND version = ?",
              rowMapper,
              projectId,
              version);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<Release> update(long projectId, long id, ReleaseInput input) {
    pinOrgContextForRls();
    try {
      // Full PUT semantics — null metadata fields clear the columns. The CLI / UI is expected to
      // re-send the full payload (it has the unchanged values from a prior GET).
      Release row =
          jdbc.queryForObject(
              "UPDATE releases SET version = ?, released_at = ?, git_sha = ?, git_ref = ?,"
                  + " deploy_stage = ?, changelog = ? WHERE project_id = ? AND id = ? RETURNING "
                  + SELECT_COLUMNS,
              new Object[] {
                input.version(),
                input.releasedAt() == null
                    ? null
                    : OffsetDateTime.ofInstant(input.releasedAt(), ZoneOffset.UTC),
                input.gitSha(),
                input.gitRef(),
                input.deployStage(),
                input.changelog(),
                projectId,
                id
              },
              new int[] {
                Types.VARCHAR,
                Types.TIMESTAMP_WITH_TIMEZONE,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.BIGINT,
                Types.BIGINT
              },
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
    Timestamp released = rs.getTimestamp("released_at");
    return new Release(
        rs.getLong("id"),
        rs.getLong("project_id"),
        rs.getString("version"),
        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
        released == null ? null : released.toInstant(),
        rs.getString("git_sha"),
        rs.getString("git_ref"),
        rs.getString("deploy_stage"),
        rs.getString("changelog"));
  }
}
