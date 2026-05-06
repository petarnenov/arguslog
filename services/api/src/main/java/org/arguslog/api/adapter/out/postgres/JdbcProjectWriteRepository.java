package org.arguslog.api.adapter.out.postgres;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.application.ProjectUseCase.DuplicateProjectException;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.domain.Project;
import org.arguslog.api.security.OrgContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcProjectWriteRepository implements ProjectWriteRepository {

  private final JdbcTemplate jdbc;

  public JdbcProjectWriteRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Project create(long orgId, String slug, String name, String platform) {
    pinOrg();
    // ON CONFLICT DO NOTHING avoids tx-poisoning under @Transactional and lets us surface a
    // domain-level duplicate exception (mapped to 409 by the controller) instead of a 500.
    Project inserted =
        jdbc.query(
            """
            INSERT INTO projects (org_id, slug, name, platform) VALUES (?, ?, ?, ?)
            ON CONFLICT (org_id, slug) DO NOTHING
            RETURNING id, created_at
            """,
            rs -> {
              if (!rs.next()) return null;
              return new Project(
                  rs.getLong("id"),
                  orgId,
                  slug,
                  name,
                  platform,
                  rs.getTimestamp("created_at").toInstant());
            },
            orgId,
            slug,
            name,
            platform);
    if (inserted == null) {
      throw new DuplicateProjectException(
          "A project with this name already exists in the organization. Please choose a different name.");
    }
    return inserted;
  }

  @Override
  public List<Project> listForOrg(long orgId) {
    pinOrg();
    // archived_at IS NULL hides soft-archived projects from the default list;
    // the partial index idx_projects_org_live keeps this index-only.
    return jdbc.query(
        """
            SELECT id, org_id, slug, name, platform, created_at
              FROM projects
             WHERE org_id = ? AND archived_at IS NULL
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
  public boolean archive(long orgId, long projectId) {
    pinOrg();
    return jdbc.update(
            """
            UPDATE projects
               SET archived_at = NOW()
             WHERE org_id = ? AND id = ? AND archived_at IS NULL
            """,
            orgId,
            projectId)
        > 0;
  }

  @Override
  public Optional<Project> find(long orgId, long projectId) {
    pinOrg();
    // Treat archived projects as "not found" for application-level lookups so a
    // bookmarked URL stops resolving once a project is archived; raw history
    // (issues/events) is still queryable directly by project_id from the worker.
    try {
      Project project =
          jdbc.queryForObject(
              """
              SELECT id, org_id, slug, name, platform, created_at
                FROM projects
               WHERE org_id = ? AND id = ? AND archived_at IS NULL
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
