package org.arguslog.worker.billing.adapter.out.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.arguslog.worker.billing.application.port.PlanExpiryRepository;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPlanExpiryRepository implements PlanExpiryRepository {

  private static final String OPEN_GRACE_SQL =
      """
      UPDATE organizations
      SET payment_grace_until = ? + (? * INTERVAL '1 second'),
          updated_at = NOW()
      WHERE plan = 'pro'
        AND plan_renews_at IS NOT NULL
        AND plan_renews_at < ?
        AND payment_grace_until IS NULL
      RETURNING id
      """;

  private final DataSource dataSource;

  public JdbcPlanExpiryRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public List<Long> openGraceForExpiredPlans(Instant now, long gracePeriodSeconds) {
    List<Long> ids = new ArrayList<>();
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(OPEN_GRACE_SQL)) {
      stmt.setObject(1, Timestamp.from(now), Types.TIMESTAMP);
      stmt.setLong(2, gracePeriodSeconds);
      stmt.setObject(3, Timestamp.from(now), Types.TIMESTAMP);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) ids.add(rs.getLong(1));
      }
    } catch (SQLException e) {
      throw new RuntimeException("Plan expiry grace open failed for " + now, e);
    }
    return ids;
  }
}
