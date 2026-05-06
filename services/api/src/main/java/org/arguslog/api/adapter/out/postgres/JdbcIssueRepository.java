package org.arguslog.api.adapter.out.postgres;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.application.CursorCodec;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.domain.Issue;
import org.arguslog.api.security.OrgContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcIssueRepository implements IssueRepository {

  // Tuple comparison `(last_seen_at, id) < (?,?)` gives a strict cursor without
  // ties; the
  // optional status / level filters are coalesced through CASE so the same
  // prepared statement
  // serves all four combinations and PG can plan it once.
  private static final String PAGE_SQL = """
      SELECT id, project_id, fingerprint, status::text, level::text, title, culprit,
             first_seen_at, last_seen_at, occurrence_count
        FROM issues
       WHERE project_id = ?
         AND (?::issue_status IS NULL OR status = ?::issue_status)
         AND (?::event_level  IS NULL OR level  = ?::event_level)
         AND (? IS NULL OR (last_seen_at, id) < (?::timestamptz, ?::bigint))
       ORDER BY last_seen_at DESC, id DESC
       LIMIT ?
      """;

  private static final String FIND_BY_PROJECT_AND_ID_SQL = """
      SELECT id, project_id, fingerprint, status::text, level::text, title, culprit,
             first_seen_at, last_seen_at, occurrence_count
        FROM issues
       WHERE project_id = ? AND id = ?
      """;

  private static final RowMapper<Issue> ROW_MAPPER = JdbcIssueRepository::mapRow;

  private final JdbcTemplate jdbc;

  public JdbcIssueRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public List<Issue> page(
      long projectId,
      Optional<Issue.Status> status,
      Optional<Issue.Level> level,
      Optional<CursorCodec.LongCursor> cursor,
      int limit) {

    String statusValue = status.map(Issue.Status::dbValue).orElse(null);
    String levelValue = level.map(Issue.Level::dbValue).orElse(null);
    Object cursorTs = cursor.map(c -> java.sql.Timestamp.from(c.instant())).orElse(null);
    Object cursorId = cursor.map(CursorCodec.LongCursor::id).orElse(null);
    Object cursorPresence = cursor.isPresent() ? Boolean.TRUE : null;

    Object[] args = {
        projectId,
        statusValue,
        statusValue,
        levelValue,
        levelValue,
        cursorPresence,
        cursorTs,
        cursorId,
        limit
    };
    int[] types = {
        Types.BIGINT,
        Types.VARCHAR,
        Types.VARCHAR,
        Types.VARCHAR,
        Types.VARCHAR,
        Types.BOOLEAN,
        Types.TIMESTAMP,
        Types.BIGINT,
        Types.INTEGER
    };

    pinOrgContextForRls();
    List<Issue> out = new ArrayList<>(limit);
    RowCallbackHandler handler = rs -> out.add(mapRow(rs, 0));
    jdbc.query(PAGE_SQL, args, types, handler);
    return out;
  }

  @Override
  public Optional<Issue> findByProjectAndId(long projectId, long issueId) {
    pinOrgContextForRls();
    try {
      return Optional.ofNullable(
          jdbc.queryForObject(FIND_BY_PROJECT_AND_ID_SQL, ROW_MAPPER, projectId, issueId));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  /**
   * Sets {@code arguslog.org_id} for the current transaction so RLS policies on
   * issues / projects
   * filter to the request's tenant. The {@code true} third arg makes this
   * {@code SET LOCAL}; the
   * caller MUST ensure a TX is active (we do via {@code @Transactional} on the
   * use case). Requires
   * OrgContext to be primed by the access guard — refusing to run a tenant-scoped
   * query with no
   * tenant is the correct behavior, not a quiet "show everything".
   */
  private void pinOrgContextForRls() {
    long orgId = OrgContext.requireCurrent();
    jdbc.queryForObject(
        "SELECT set_config('arguslog.org_id', ?, true)", String.class, String.valueOf(orgId));
  }

  private static Issue mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new Issue(
        rs.getLong("id"),
        rs.getLong("project_id"),
        rs.getString("fingerprint"),
        Issue.Status.fromString(rs.getString("status")),
        Issue.Level.fromString(rs.getString("level")),
        rs.getString("title"),
        rs.getString("culprit"),
        rs.getTimestamp("first_seen_at").toInstant(),
        rs.getTimestamp("last_seen_at").toInstant(),
        rs.getLong("occurrence_count"));
  }
}
