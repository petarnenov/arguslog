package org.arguslog.api.adapter.out.postgres;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.application.CursorCodec;
import org.arguslog.api.application.ListIssuesUseCase.AssigneeFilter;
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

  // Tuple comparison `(last_seen_at, id) < (?,?)` gives a strict cursor without ties; the
  // optional status / level / search / assignee filters are coalesced through CASE / IS NULL
  // so the same prepared statement serves every combination and PG can plan it once.
  //
  // searchText: NULL → match all; non-null → ILIKE substring on (title, culprit).
  // assignee_mode:
  //   NULL  → no assignee filter (match all)
  //   ''    → unassigned only (assignee_user_id IS NULL)
  //   <uuid string> → exact match on that user
  // LEFT JOIN on releases brings back the version string for "first seen in vX.Y.Z" rendering
  // without a separate round-trip. The join is cheap because first_seen_release_id is indexed
  // (V35) and the typical column is NULL.
  private static final String PAGE_SQL =
      """
      SELECT i.id, i.project_id, i.fingerprint, i.status::text, i.level::text, i.title, i.culprit,
             i.first_seen_at, i.last_seen_at, i.occurrence_count, i.assignee_user_id,
             i.first_seen_release_id, r.version AS first_seen_release_version
        FROM issues i
        LEFT JOIN releases r ON r.id = i.first_seen_release_id
       WHERE i.project_id = ?
         AND (?::issue_status IS NULL OR i.status = ?::issue_status)
         AND (?::event_level  IS NULL OR i.level  = ?::event_level)
         AND (?::text IS NULL OR (i.title ILIKE ?::text OR COALESCE(i.culprit,'') ILIKE ?::text))
         AND (?::text IS NULL
              OR (?::text = '' AND i.assignee_user_id IS NULL)
              OR (?::text <> '' AND i.assignee_user_id = ?::uuid))
         AND (? IS NULL OR (i.last_seen_at, i.id) < (?::timestamptz, ?::bigint))
       ORDER BY i.last_seen_at DESC, i.id DESC
       LIMIT ?
      """;

  private static final String FIND_BY_PROJECT_AND_ID_SQL =
      """
      SELECT i.id, i.project_id, i.fingerprint, i.status::text, i.level::text, i.title, i.culprit,
             i.first_seen_at, i.last_seen_at, i.occurrence_count, i.assignee_user_id,
             i.first_seen_release_id, r.version AS first_seen_release_version
        FROM issues i
        LEFT JOIN releases r ON r.id = i.first_seen_release_id
       WHERE i.project_id = ? AND i.id = ?
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
      Optional<String> searchText,
      Optional<AssigneeFilter> assignee,
      Optional<CursorCodec.LongCursor> cursor,
      int limit) {

    String statusValue = status.map(Issue.Status::dbValue).orElse(null);
    String levelValue = level.map(Issue.Level::dbValue).orElse(null);
    // Wrap the search text with %…% on both sides so ILIKE matches anywhere in the column.
    // Blank inputs are treated as "no filter" — defensive against the frontend sending an
    // empty string instead of dropping the param.
    String searchPattern =
        searchText
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> "%" + s + "%")
            .orElse(null);
    // Encode the assignee filter as a single text mode arg used three times in the SQL:
    //   null → no filter; "" → unassigned-only; "<uuid>" → match exact user.
    String assigneeMode =
        assignee
            .map(
                f ->
                    switch (f) {
                      case AssigneeFilter.User u -> u.userId().toString();
                      case AssigneeFilter.Unassigned __ -> "";
                    })
            .orElse(null);
    Object cursorTs = cursor.map(c -> java.sql.Timestamp.from(c.instant())).orElse(null);
    Object cursorId = cursor.map(CursorCodec.LongCursor::id).orElse(null);
    Object cursorPresence = cursor.isPresent() ? Boolean.TRUE : null;

    Object[] args = {
      projectId,
      statusValue,
      statusValue,
      levelValue,
      levelValue,
      searchPattern,
      searchPattern,
      searchPattern,
      assigneeMode,
      assigneeMode,
      assigneeMode,
      assigneeMode,
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
      Types.VARCHAR,
      Types.VARCHAR,
      Types.VARCHAR,
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

  @Override
  public Optional<Issue> updateStatus(long projectId, long issueId, Issue.Status status) {
    pinOrgContextForRls();
    // The cast to issue_status ensures we surface a 400 (rather than silently miss) if the
    // dbValue() ever drifts from the enum members.
    int rows =
        jdbc.update(
            "UPDATE issues SET status = ?::issue_status WHERE project_id = ? AND id = ?",
            status.dbValue(),
            projectId,
            issueId);
    if (rows == 0) return Optional.empty();
    return findByProjectAndId(projectId, issueId);
  }

  @Override
  public Optional<Issue> updateAssignee(long projectId, long issueId, UUID assigneeUserId) {
    pinOrgContextForRls();
    int rows =
        jdbc.update(
            "UPDATE issues SET assignee_user_id = ? WHERE project_id = ? AND id = ?",
            assigneeUserId,
            projectId,
            issueId);
    if (rows == 0) return Optional.empty();
    return findByProjectAndId(projectId, issueId);
  }

  /**
   * Sets {@code arguslog.org_id} for the current transaction so RLS policies on issues / projects
   * filter to the request's tenant. The {@code true} third arg makes this {@code SET LOCAL}; the
   * caller MUST ensure a TX is active (we do via {@code @Transactional} on the use case). Requires
   * OrgContext to be primed by the access guard — refusing to run a tenant-scoped query with no
   * tenant is the correct behavior, not a quiet "show everything".
   */
  private void pinOrgContextForRls() {
    long orgId = OrgContext.requireCurrent();
    jdbc.queryForObject(
        "SELECT set_config('arguslog.org_id', ?, true)", String.class, String.valueOf(orgId));
  }

  private static Issue mapRow(ResultSet rs, int rowNum) throws SQLException {
    Object assigneeObj = rs.getObject("assignee_user_id");
    UUID assignee = assigneeObj instanceof UUID u ? u : null;
    long releaseIdRaw = rs.getLong("first_seen_release_id");
    Long releaseId = rs.wasNull() ? null : releaseIdRaw;
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
        rs.getLong("occurrence_count"),
        assignee,
        releaseId,
        rs.getString("first_seen_release_version"));
  }
}
