package org.arguslog.worker.retention.adapter.out.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import javax.sql.DataSource;
import org.arguslog.worker.retention.application.port.RetentionPurgeRepository;
import org.springframework.stereotype.Repository;

/**
 * Batched DELETE against the {@code events} hypertable. Postgres doesn't support {@code DELETE …
 * LIMIT}, so the batch is expressed as a sub-select with the composite primary key {@code (id,
 * received_at)} (required because TimescaleDB partitioning forces {@code received_at} into any
 * unique constraint).
 *
 * <p>The sub-select carries the {@code project_id} predicate so the planner can prune chunks via
 * the {@code idx_events_project_time} index instead of scanning every chunk.
 */
@Repository
public class JdbcRetentionPurgeRepository implements RetentionPurgeRepository {

  private static final String DELETE_BATCH =
      """
      DELETE FROM events
      WHERE (id, received_at) IN (
        SELECT e.id, e.received_at
        FROM events e
        JOIN projects p ON p.id = e.project_id
        WHERE p.org_id = ? AND e.received_at < ?
        LIMIT ?
      )
      """;

  private static final String COUNT_ELIGIBLE =
      """
      SELECT COUNT(*)
      FROM events e
      JOIN projects p ON p.id = e.project_id
      WHERE p.org_id = ? AND e.received_at < ?
      """;

  private final DataSource dataSource;

  public JdbcRetentionPurgeRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public int purgeBatch(long orgId, Instant cutoff, int batchSize) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(DELETE_BATCH)) {
      stmt.setLong(1, orgId);
      // TIMESTAMP_WITH_TIMEZONE rejects java.sql.Timestamp on the PG driver — Types.TIMESTAMP +
      // a UTC-anchored Timestamp is what TIMESTAMPTZ columns actually expect through JDBC.
      stmt.setObject(2, Timestamp.from(cutoff), Types.TIMESTAMP);
      stmt.setInt(3, batchSize);
      return stmt.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(
          "Retention purge failed for org=" + orgId + " cutoff=" + cutoff, e);
    }
  }

  @Override
  public long countEligible(long orgId, Instant cutoff) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(COUNT_ELIGIBLE)) {
      stmt.setLong(1, orgId);
      stmt.setObject(2, Timestamp.from(cutoff), Types.TIMESTAMP);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (SQLException e) {
      throw new RuntimeException(
          "Retention count failed for org=" + orgId + " cutoff=" + cutoff, e);
    }
  }
}
