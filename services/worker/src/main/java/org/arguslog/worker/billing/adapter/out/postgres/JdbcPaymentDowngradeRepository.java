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
import org.arguslog.worker.billing.application.port.PaymentDowngradeRepository;
import org.springframework.stereotype.Repository;

/**
 * Per-user payment downgrade (V27+). Billing identity lives on users, so the worker downgrades
 * the user row directly when their grace window expires and returns the affected org ids
 * (resolved from the downgraded users' owned orgs) so the orchestrator can emit per-org audit /
 * alert events with the same shape it had before V27.
 *
 * <p>The {@code plan != 'free'} predicate catches every paid tier — STARTER / PRO / BUSINESS /
 * ENTERPRISE. The original {@code plan = 'pro'} predicate was a pre-V23 holdover that quietly
 * left starter/business customers sitting in grace forever.
 */
@Repository
public class JdbcPaymentDowngradeRepository implements PaymentDowngradeRepository {

  private static final String DOWNGRADE_SQL =
      """
      WITH downgraded AS (
        UPDATE users
           SET plan = 'free',
               payment_grace_until = NULL,
               plan_renews_at = NULL
         WHERE plan != 'free'::org_plan
           AND payment_grace_until IS NOT NULL
           AND payment_grace_until < ?
        RETURNING id
      )
      SELECT DISTINCT m.org_id
        FROM downgraded d
        JOIN org_members m ON m.user_id = d.id AND m.role = 'owner'::org_role
      """;

  private final DataSource dataSource;

  public JdbcPaymentDowngradeRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public List<Long> downgradeExpired(Instant now) {
    List<Long> orgIds = new ArrayList<>();
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(DOWNGRADE_SQL)) {
      stmt.setObject(1, Timestamp.from(now), Types.TIMESTAMP);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) orgIds.add(rs.getLong(1));
      }
    } catch (SQLException e) {
      throw new RuntimeException("Payment downgrade failed for cutoff " + now, e);
    }
    return orgIds;
  }
}
