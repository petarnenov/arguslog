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
   */
  private static final String UPSERT_ISSUE_SQL =
      """
      INSERT INTO issues (project_id, environment_id, fingerprint, status, level, title, culprit,
                          first_seen_at, last_seen_at, occurrence_count)
           VALUES (?, NULL, ?, 'unresolved', ?::event_level, ?, ?, ?, ?, 1)
      ON CONFLICT (project_id, environment_id, fingerprint)
        DO UPDATE SET last_seen_at     = GREATEST(issues.last_seen_at, EXCLUDED.last_seen_at),
                      occurrence_count = issues.occurrence_count + 1
        RETURNING id, (xmax = 0) AS is_insert
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
  public PersistResult persist(IncomingEvent event, Fingerprint fingerprint) {
    UpsertRow row = upsertIssue(event, fingerprint);
    insertEvent(event, row.issueId);
    return new PersistResult(row.issueId, row.isInsert);
  }

  private UpsertRow upsertIssue(IncomingEvent event, Fingerprint fingerprint) {
    try {
      return jdbc.queryForObject(
          UPSERT_ISSUE_SQL,
          (rs, rowNum) -> new UpsertRow(rs.getLong("id"), rs.getBoolean("is_insert")),
          event.projectId(),
          fingerprint.hash(),
          fingerprint.level().dbValue(),
          fingerprint.title(),
          fingerprint.culprit(),
          java.sql.Timestamp.from(event.receivedAt()),
          java.sql.Timestamp.from(event.receivedAt()));
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

  private record UpsertRow(long issueId, boolean isInsert) {}
}
