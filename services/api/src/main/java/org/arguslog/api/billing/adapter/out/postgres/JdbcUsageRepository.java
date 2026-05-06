package org.arguslog.api.billing.adapter.out.postgres;

import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.arguslog.api.billing.application.port.UsageRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcUsageRepository implements UsageRepository {

  private final JdbcTemplate jdbc;

  public JdbcUsageRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public long currentEventCount(long orgId, LocalDate periodStart) {
    Long row =
        jdbc.queryForObject(
            // COALESCE so a missing row → 0 (no events yet this month).
            "SELECT COALESCE(MAX(events_count), 0) FROM quotas"
                + " WHERE org_id = ? AND period_start = ?",
            new Object[] {orgId, Date.valueOf(periodStart)},
            new int[] {Types.BIGINT, Types.DATE},
            Long.class);
    return row == null ? 0L : row;
  }
}
