package org.arguslog.ingest.adapter.out.postgres;

import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.arguslog.ingest.application.port.MonthlyQuotaCounter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Atomic UPSERT-with-check. Single round-trip:
 *
 * <ul>
 *   <li>If no row exists for {@code (org_id, period_start)} — INSERT with count=1, treat as allowed
 *       (1 ≤ cap is always true for cap > 0; if cap is 0 the WHERE on the DO UPDATE branch never
 *       fires, but the INSERT does — guard there too).
 *   <li>If a row exists and its count is below cap — increment, return new count.
 *   <li>If a row exists and its count is at-or-above cap — DO UPDATE's WHERE blocks the write,
 *       RETURNING is empty, caller treats as quota exceeded.
 * </ul>
 */
@Component
public class JdbcMonthlyQuotaCounter implements MonthlyQuotaCounter {

  private static final String SQL =
      """
      INSERT INTO quotas (org_id, period_start, events_count)
           VALUES (?, ?, 1)
      ON CONFLICT (org_id, period_start) DO UPDATE
           SET events_count = quotas.events_count + 1
         WHERE quotas.events_count < ?
      RETURNING events_count
      """;

  private final JdbcTemplate jdbc;

  public JdbcMonthlyQuotaCounter(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public boolean tryConsume(long orgId, LocalDate periodStart, long cap) {
    if (cap <= 0) return false; // FREE-but-disabled or misconfigured tier
    Long resultingCount =
        jdbc.query(
            SQL,
            new Object[] {orgId, Date.valueOf(periodStart), cap},
            new int[] {Types.BIGINT, Types.DATE, Types.BIGINT},
            rs -> rs.next() ? rs.getLong(1) : null);
    return resultingCount != null;
  }
}
