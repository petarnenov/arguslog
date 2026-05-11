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

/**
 * Per-user plan-expiry grace opener (V27+). Billing identity lives on users, so the worker writes
 * {@code payment_grace_until} on the user row directly and returns the affected owner-org ids
 * (resolved via JOIN to org_members) so callers can audit / alert with the same shape they had
 * before V27 — mirroring {@link JdbcPaymentDowngradeRepository}.
 *
 * <p>The {@code plan != 'free'} predicate catches every paid tier — STARTER / PRO / BUSINESS /
 * ENTERPRISE. The pre-V23 {@code plan = 'pro'} predicate was a holdover that silently let expired
 * starter/business plans stay un-graced; together with the now-fixed downgrade repo this closes the
 * per-user-billing time-driven expiry path end-to-end.
 */
@Repository
public class JdbcPlanExpiryRepository implements PlanExpiryRepository {

  private static final String OPEN_GRACE_SQL =
      """
      WITH opened AS (
        UPDATE users
           SET payment_grace_until = ?::timestamptz + make_interval(secs => ?)
         WHERE plan != 'free'::org_plan
           AND plan_renews_at IS NOT NULL
           AND plan_renews_at < ?::timestamptz
           AND payment_grace_until IS NULL
        RETURNING id
      )
      SELECT DISTINCT m.org_id
        FROM opened o
        JOIN org_members m ON m.user_id = o.id AND m.role = 'owner'::org_role
      """;

  private final DataSource dataSource;

  public JdbcPlanExpiryRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public List<Long> openGraceForExpiredPlans(Instant now, long gracePeriodSeconds) {
    List<Long> orgIds = new ArrayList<>();
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(OPEN_GRACE_SQL)) {
      stmt.setObject(1, Timestamp.from(now), Types.TIMESTAMP);
      stmt.setLong(2, gracePeriodSeconds);
      stmt.setObject(3, Timestamp.from(now), Types.TIMESTAMP);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) orgIds.add(rs.getLong(1));
      }
    } catch (SQLException e) {
      // Message intentionally has no timestamp — Arguslog fingerprints by exception.value, so a
      // mutable timestamp here splays every hourly run into its own "issue" instead of one
      // 24×-counter issue. The receivedAt + payload.timestamp on the event still carry the time.
      throw new RuntimeException("Plan expiry grace open failed", e);
    }
    return orgIds;
  }
}
