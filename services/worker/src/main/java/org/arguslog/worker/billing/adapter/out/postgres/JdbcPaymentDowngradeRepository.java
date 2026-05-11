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
 * Single-transaction downgrade with {@code RETURNING id} so the orchestrator gets the affected ids
 * without a follow-up SELECT that would race concurrent webhook writes.
 *
 * <p>The {@code plan != 'free'} predicate is required even though grace is set only on paid orgs —
 * Stripe could deliver a {@code customer.subscription.deleted} after grace was opened but before
 * the worker runs, leaving the row on Free with a stale grace timestamp; the predicate stops the
 * worker from "downgrading" an already-Free org and emitting noise audit events.
 *
 * <p>Per-user billing (V26+): the downgrade mirrors onto the user rows of every affected org's
 * owners, so cap-checks (which read users.plan as the source of truth) flip to Free in lockstep
 * with the org row. Without the mirror the user would keep their cached paid tier until the next
 * webhook touched their user row.
 */
@Repository
public class JdbcPaymentDowngradeRepository implements PaymentDowngradeRepository {

  private static final String DOWNGRADE_ORGS_SQL =
      """
      UPDATE organizations
      SET plan = 'free',
          payment_grace_until = NULL,
          plan_renews_at = NULL,
          updated_at = NOW()
      WHERE plan != 'free'
        AND payment_grace_until IS NOT NULL
        AND payment_grace_until < ?
      RETURNING id
      """;

  private static final String DOWNGRADE_OWNER_USERS_SQL =
      """
      UPDATE users u
         SET plan = 'free',
             payment_grace_until = NULL,
             plan_renews_at = NULL
        FROM org_members m
       WHERE m.user_id = u.id
         AND m.role = 'owner'::org_role
         AND m.org_id = ANY (?)
      """;

  private final DataSource dataSource;

  public JdbcPaymentDowngradeRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public List<Long> downgradeExpired(Instant now) {
    List<Long> ids = new ArrayList<>();
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      try {
        try (PreparedStatement stmt = conn.prepareStatement(DOWNGRADE_ORGS_SQL)) {
          stmt.setObject(1, Timestamp.from(now), Types.TIMESTAMP);
          try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) ids.add(rs.getLong(1));
          }
        }
        if (!ids.isEmpty()) {
          try (PreparedStatement stmt = conn.prepareStatement(DOWNGRADE_OWNER_USERS_SQL)) {
            Long[] idArray = ids.toArray(new Long[0]);
            stmt.setArray(1, conn.createArrayOf("bigint", idArray));
            stmt.executeUpdate();
          }
        }
        conn.commit();
      } catch (SQLException e) {
        conn.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Payment downgrade failed for cutoff " + now, e);
    }
    return ids;
  }
}
