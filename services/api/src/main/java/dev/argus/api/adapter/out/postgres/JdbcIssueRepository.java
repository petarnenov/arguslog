package dev.argus.api.adapter.out.postgres;

import dev.argus.api.application.port.IssueRepository;
import dev.argus.api.domain.Issue;
import dev.argus.api.security.OrgContext;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcIssueRepository implements IssueRepository {

  // Tuple comparison `(last_seen_at, id) < (?,?)` gives a strict cursor without ties; the
  // optional status / level filters are coalesced through CASE so the same prepared statement
  // serves all four combinations and PG can plan it once.
  private static final String PAGE_SQL =
      """
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

  private final JdbcTemplate jdbc;

  public JdbcIssueRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public List<Issue> page(
      long projectId,
      Optional<Issue.Status> status,
      Optional<Issue.Level> level,
      Optional<Cursor> cursor,
      int limit) {

    String statusValue = status.map(Issue.Status::dbValue).orElse(null);
    String levelValue = level.map(Issue.Level::dbValue).orElse(null);
    Object cursorTs = cursor.map(c -> java.sql.Timestamp.from(c.lastSeenAt())).orElse(null);
    Object cursorId = cursor.map(Cursor::id).orElse(null);
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
    jdbc.query(
        PAGE_SQL,
        args,
        types,
        rs -> {
          out.add(
              new Issue(
                  rs.getLong("id"),
                  rs.getLong("project_id"),
                  rs.getString("fingerprint"),
                  Issue.Status.fromString(rs.getString("status")),
                  Issue.Level.fromString(rs.getString("level")),
                  rs.getString("title"),
                  rs.getString("culprit"),
                  rs.getTimestamp("first_seen_at").toInstant(),
                  rs.getTimestamp("last_seen_at").toInstant(),
                  rs.getLong("occurrence_count")));
        });
    return out;
  }

  /**
   * Sets {@code argus.org_id} for the current transaction so RLS policies on issues / projects
   * filter to the request's tenant. The {@code true} third arg makes this {@code SET LOCAL}; the
   * caller MUST ensure a TX is active (we do via {@code @Transactional} on the use case). Requires
   * OrgContext to be primed by the access guard — refusing to run a tenant-scoped query with no
   * tenant is the correct behavior, not a quiet "show everything".
   */
  private void pinOrgContextForRls() {
    long orgId = OrgContext.requireCurrent();
    jdbc.queryForObject(
        "SELECT set_config('argus.org_id', ?, true)", String.class, String.valueOf(orgId));
  }
}
