package org.arguslog.worker.adapter.out.postgres;

import java.sql.Types;
import javax.sql.DataSource;
import org.arguslog.worker.application.port.EventStore;
import org.arguslog.worker.domain.Fingerprint;
import org.arguslog.worker.domain.IncomingEvent;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JdbcEventStore implements EventStore {

  /**
   * Atomically upserts the issue row keyed by (project_id, fingerprint) and bumps stats. Uses ON
   * CONFLICT DO UPDATE so concurrent worker replicas can't race; the xmax trick reports whether a
   * row was actually inserted (vs. updated).
   *
   * <p>Auto-regression: when an event arrives on an issue that was previously {@code resolved},
   * we flip status back to {@code unresolved}. This is the "the bug came back" signal — the
   * triage UI sorts/filters on status, so the issue reappears in the unresolved queue without
   * losing its history. {@code ignored} is intentionally NOT flipped — ignoring is "I never want
   * to hear about this again", whereas resolved is "I fixed it and want to know if it returns".
   */
  // first_seen_release_id is resolved by an in-SQL sub-select against `releases` so we don't
  // need a parallel repository port. The sub-select returns NULL when (project_id, version)
  // doesn't match — desired behaviour (event tagged with an unknown release leaves the column
  // NULL rather than failing the insert). On UPDATE, the column is intentionally NOT touched:
  // "first seen" is immutable.
  private static final String UPSERT_ISSUE_SQL =
      """
      INSERT INTO issues (project_id, environment_id, fingerprint, status, level, title, culprit,
                          first_seen_at, last_seen_at, occurrence_count, first_seen_release_id)
           VALUES (?, NULL, ?, 'unresolved', ?::event_level, ?, ?, ?, ?, 1,
                   (SELECT id FROM releases
                     WHERE project_id = ? AND version = ?
                     LIMIT 1))
      ON CONFLICT (project_id, environment_id, fingerprint)
        DO UPDATE SET last_seen_at     = GREATEST(issues.last_seen_at, EXCLUDED.last_seen_at),
                      occurrence_count = issues.occurrence_count + 1,
                      status           = CASE
                                           WHEN issues.status = 'resolved' THEN 'unresolved'::issue_status
                                           ELSE issues.status
                                         END
        RETURNING id, (xmax = 0) AS is_insert,
                  level::text AS level_text, first_seen_at, last_seen_at, occurrence_count
      """;

  private static final String INSERT_EVENT_SQL =
      """
      INSERT INTO events (id, issue_id, project_id, environment_id, received_at, payload)
           VALUES (?, ?, ?, NULL, ?, ?::jsonb)
      ON CONFLICT (id, received_at) DO NOTHING
      """;

  // Postgres can't have ON CONFLICT (project_id, environment_id, fingerprint) when environment_id
  // is NULL — the unique index treats NULL as distinct. We work around by enforcing uniqueness in
  // a partial index that treats NULL as a fixed sentinel. See V2 migration.
  private final JdbcTemplate jdbc;

  public JdbcEventStore(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  @Transactional
  public PersistResult persist(IncomingEvent event, Fingerprint fingerprint, String releaseVersion) {
    PersistResult row = upsertIssue(event, fingerprint, releaseVersion);
    insertEvent(event, row.issueId());
    return row;
  }

  private PersistResult upsertIssue(
      IncomingEvent event, Fingerprint fingerprint, String releaseVersion) {
    try {
      return jdbc.queryForObject(
          UPSERT_ISSUE_SQL,
          (rs, rowNum) ->
              new PersistResult(
                  rs.getLong("id"),
                  rs.getBoolean("is_insert"),
                  rs.getString("level_text"),
                  rs.getTimestamp("first_seen_at").toInstant(),
                  rs.getTimestamp("last_seen_at").toInstant(),
                  rs.getLong("occurrence_count")),
          event.projectId(),
          fingerprint.hash(),
          fingerprint.level().dbValue(),
          fingerprint.title(),
          fingerprint.culprit(),
          java.sql.Timestamp.from(event.receivedAt()),
          java.sql.Timestamp.from(event.receivedAt()),
          // Sub-select args (project_id + version). Pass null version when the event didn't
          // carry a release tag; the SELECT id FROM releases WHERE version = NULL returns no
          // rows, so the column lands NULL — same result as no match.
          event.projectId(),
          releaseVersion);
    } catch (EmptyResultDataAccessException e) {
      throw new IllegalStateException("Issue upsert returned no row — schema drift?", e);
    }
  }

  private void insertEvent(IncomingEvent event, long issueId) {
    jdbc.update(
        INSERT_EVENT_SQL,
        new Object[] {
          event.eventId(),
          issueId,
          event.projectId(),
          java.sql.Timestamp.from(event.receivedAt()),
          event.rawPayload()
        },
        new int[] {Types.OTHER, Types.BIGINT, Types.BIGINT, Types.TIMESTAMP, Types.OTHER});
  }
}
