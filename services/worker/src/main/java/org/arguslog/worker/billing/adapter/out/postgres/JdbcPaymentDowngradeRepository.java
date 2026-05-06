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
 * Single-statement downgrade with {@code RETURNING id} so the orchestrator gets the affected ids
 * without a follow-up SELECT that would race concurrent webhook writes.
 *
 * <p>The {@code plan = 'pro'} predicate is required even though grace is set only on Pro orgs —
 * Stripe could deliver a {@code customer.subscription.deleted} after grace was opened but before
 * the worker runs, leaving the row on Free with a stale grace timestamp; the predicate stops the
 * worker from "downgrading" an already-Free org and emitting noise audit events.
 */
@Repository
public class JdbcPaymentDowngradeRepository implements PaymentDowngradeRepository {

  private static final String DOWNGRADE_SQL =
      """
      UPDATE organizations
      SET plan = 'free',
          payment_grace_until = NULL,
          plan_renews_at = NULL,
          updated_at = NOW()
      WHERE plan = 'pro'
        AND payment_grace_until IS NOT NULL
        AND payment_grace_until < ?
      RETURNING id
      """;

  private final DataSource dataSource;

  public JdbcPaymentDowngradeRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public List<Long> downgradeExpired(Instant now) {
    List<Long> ids = new ArrayList<>();
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(DOWNGRADE_SQL)) {
      stmt.setObject(1, Timestamp.from(now), Types.TIMESTAMP);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) ids.add(rs.getLong(1));
      }
    } catch (SQLException e) {
      throw new RuntimeException("Payment downgrade failed for cutoff " + now, e);
    }
    return ids;
  }
}
