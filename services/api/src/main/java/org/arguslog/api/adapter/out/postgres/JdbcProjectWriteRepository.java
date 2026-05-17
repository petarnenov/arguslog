package org.arguslog.api.adapter.out.postgres;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.application.ProjectUseCase.DuplicateProjectException;
import org.arguslog.api.application.dto.ProjectStats;
import org.arguslog.api.application.dto.ProjectStats.DailyEventBucket;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.domain.GitProvider;
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
  public Project create(
      long orgId,
      String slug,
      String name,
      String platform,
      GitProvider gitProvider,
      String gitRepo) {
    pinOrg();
    // ON CONFLICT DO NOTHING avoids tx-poisoning under @Transactional and lets us surface a
    // domain-level duplicate exception (mapped to 409 by the controller) instead of a 500.
    String providerDb = gitProvider == null ? null : gitProvider.dbValue();
    Project inserted =
        jdbc.query(
            """
            INSERT INTO projects (org_id, slug, name, platform, git_provider, git_repo)
            VALUES (?, ?, ?, ?, ?, ?)
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
                  rs.getTimestamp("created_at").toInstant(),
                  gitProvider,
                  gitRepo);
            },
            orgId,
            slug,
            name,
            platform,
            providerDb,
            gitRepo);
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
            SELECT id, org_id, slug, name, platform, created_at, git_provider, git_repo
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
                rs.getTimestamp("created_at").toInstant(),
                GitProvider.fromDbValue(rs.getString("git_provider")).orElse(null),
                rs.getString("git_repo")),
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
  public Optional<Project> rename(long orgId, long projectId, String name) {
    pinOrg();
    int updated =
        jdbc.update(
            """
            UPDATE projects
               SET name = ?
             WHERE org_id = ? AND id = ? AND archived_at IS NULL
            """,
            name,
            orgId,
            projectId);
    if (updated == 0) return Optional.empty();
    return find(orgId, projectId);
  }

  @Override
  public Optional<Project> updateGitRepo(
      long orgId, long projectId, GitProvider provider, String repo) {
    pinOrg();
    String providerDb = provider == null ? null : provider.dbValue();
    int updated =
        jdbc.update(
            """
            UPDATE projects
               SET git_provider = ?, git_repo = ?
             WHERE org_id = ? AND id = ? AND archived_at IS NULL
            """,
            providerDb,
            repo,
            orgId,
            projectId);
    if (updated == 0) return Optional.empty();
    return find(orgId, projectId);
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
              SELECT id, org_id, slug, name, platform, created_at, git_provider, git_repo
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
                      rs.getTimestamp("created_at").toInstant(),
                      GitProvider.fromDbValue(rs.getString("git_provider")).orElse(null),
                      rs.getString("git_repo")),
              orgId,
              projectId);
      return Optional.ofNullable(project);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private static final int SPARKLINE_DAYS = 14;

  @Override
  public Map<Long, ProjectStats> statsForOrg(long orgId) {
    pinOrg();

    // 1. Unresolved issue counts per project.
    Map<Long, Integer> unresolved = new HashMap<>();
    jdbc.query(
        """
        SELECT i.project_id, COUNT(*)::int AS n
          FROM issues i
          JOIN projects p ON p.id = i.project_id
         WHERE p.org_id = ?
           AND p.archived_at IS NULL
           AND i.status = 'unresolved'
         GROUP BY i.project_id
        """,
        rs -> {
          unresolved.put(rs.getLong("project_id"), rs.getInt("n"));
        },
        orgId);

    // 2. Events in last 24h.
    Map<Long, Long> events24h = countEvents(orgId, "24 hours");
    // 3. Events in last 7d.
    Map<Long, Long> events7d = countEvents(orgId, "7 days");

    // 4. Last event timestamp per project.
    Map<Long, Instant> lastEvent = new HashMap<>();
    jdbc.query(
        """
        SELECT e.project_id, MAX(e.received_at) AS last_at
          FROM events e
          JOIN projects p ON p.id = e.project_id
         WHERE p.org_id = ?
           AND p.archived_at IS NULL
         GROUP BY e.project_id
        """,
        rs -> {
          Timestamp ts = rs.getTimestamp("last_at");
          if (ts != null) lastEvent.put(rs.getLong("project_id"), ts.toInstant());
        },
        orgId);

    // 5. Daily event buckets for the last 14 days.
    Map<Long, Map<LocalDate, Long>> buckets = new HashMap<>();
    jdbc.query(
        """
        SELECT e.project_id,
               date_trunc('day', e.received_at AT TIME ZONE 'UTC')::date AS day,
               COUNT(*)::bigint AS n
          FROM events e
          JOIN projects p ON p.id = e.project_id
         WHERE p.org_id = ?
           AND p.archived_at IS NULL
           AND e.received_at > NOW() - INTERVAL '14 days'
         GROUP BY e.project_id, day
         ORDER BY e.project_id, day
        """,
        rs -> {
          long pid = rs.getLong("project_id");
          LocalDate day = rs.getDate("day").toLocalDate();
          long n = rs.getLong("n");
          buckets.computeIfAbsent(pid, k -> new HashMap<>()).put(day, n);
        },
        orgId);

    // Compose one ProjectStats per project that appears in any of the maps above.
    java.util.Set<Long> projectIds = new java.util.HashSet<>();
    projectIds.addAll(unresolved.keySet());
    projectIds.addAll(events24h.keySet());
    projectIds.addAll(events7d.keySet());
    projectIds.addAll(lastEvent.keySet());
    projectIds.addAll(buckets.keySet());

    Map<Long, ProjectStats> out = new LinkedHashMap<>();
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    for (long pid : projectIds) {
      Map<LocalDate, Long> hits = buckets.getOrDefault(pid, Map.of());
      List<DailyEventBucket> series = new ArrayList<>(SPARKLINE_DAYS);
      for (int i = SPARKLINE_DAYS - 1; i >= 0; i--) {
        LocalDate day = today.minusDays(i);
        series.add(new DailyEventBucket(day, hits.getOrDefault(day, 0L)));
      }
      out.put(
          pid,
          new ProjectStats(
              unresolved.getOrDefault(pid, 0),
              events24h.getOrDefault(pid, 0L),
              events7d.getOrDefault(pid, 0L),
              lastEvent.get(pid),
              List.copyOf(series)));
    }
    return out;
  }

  private Map<Long, Long> countEvents(long orgId, String interval) {
    Map<Long, Long> out = new HashMap<>();
    // String concatenation for the interval is safe because the caller passes a literal —
    // never user input — but keep the receivedAt filter as an inline interval since
    // JdbcTemplate doesn't bind INTERVAL values cleanly.
    jdbc.query(
        "SELECT e.project_id, COUNT(*)::bigint AS n "
            + "FROM events e JOIN projects p ON p.id = e.project_id "
            + "WHERE p.org_id = ? AND p.archived_at IS NULL "
            + "AND e.received_at > NOW() - INTERVAL '"
            + interval
            + "' "
            + "GROUP BY e.project_id",
        rs -> {
          out.put(rs.getLong("project_id"), rs.getLong("n"));
        },
        orgId);
    return out;
  }

  private void pinOrg() {
    long orgId = OrgContext.requireCurrent();
    jdbc.queryForObject(
        "SELECT set_config('arguslog.org_id', ?, true)", String.class, String.valueOf(orgId));
  }
}
