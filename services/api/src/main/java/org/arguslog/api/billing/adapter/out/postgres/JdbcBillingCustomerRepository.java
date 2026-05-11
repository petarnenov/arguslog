package org.arguslog.api.billing.adapter.out.postgres;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcBillingCustomerRepository implements BillingCustomerRepository {

  private final JdbcTemplate jdbc;

  public JdbcBillingCustomerRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Optional<String> findCustomerId(long orgId) {
    try {
      String id =
          jdbc.queryForObject(
              "SELECT stripe_customer_id FROM organizations WHERE id = ?", String.class, orgId);
      return Optional.ofNullable(id).filter(s -> !s.isBlank());
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<Long> findOrgIdByCustomerId(String customerId) {
    try {
      Long id =
          jdbc.queryForObject(
              "SELECT id FROM organizations WHERE stripe_customer_id = ?", Long.class, customerId);
      return Optional.ofNullable(id);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public void saveCustomerId(long orgId, String customerId) {
    jdbc.update(
        "UPDATE organizations SET stripe_customer_id = ?, updated_at = NOW() WHERE id = ?",
        customerId,
        orgId);
    // Dual-write Stripe customer identity onto the org's primary owner so the per-user billing
    // path (V26+) can resolve "which user is this Stripe customer" without a JOIN through orgs.
    jdbc.update(
        "UPDATE users SET stripe_customer_id = ? WHERE id = " + primaryOwnerSubquery(),
        customerId,
        orgId);
  }

  @Override
  public void updatePlanAndRenewal(long orgId, String planDbValue, Instant renewsAt) {
    jdbc.update(
        "UPDATE organizations SET plan = ?::org_plan, plan_renews_at = ?, updated_at = NOW()"
            + " WHERE id = ?",
        new Object[] {planDbValue, renewsAt == null ? null : Timestamp.from(renewsAt), orgId},
        // Use TIMESTAMP not TIMESTAMP_WITH_TIMEZONE — the latter refuses java.sql.Timestamp; the
        // Postgres JDBC driver coerces UTC-anchored Timestamps into TIMESTAMPTZ correctly anyway.
        new int[] {Types.OTHER, Types.TIMESTAMP, Types.BIGINT});
    // Mirror onto the org's primary owner — readers (cap-checks, admin table) consult users.plan
    // since V26, so without this dual-write a Stripe upgrade would silently miss the cap raise.
    jdbc.update(
        "UPDATE users SET plan = ?::org_plan, plan_renews_at = ? WHERE id = " + primaryOwnerSubquery(),
        new Object[] {planDbValue, renewsAt == null ? null : Timestamp.from(renewsAt), orgId},
        new int[] {Types.OTHER, Types.TIMESTAMP, Types.BIGINT});
  }

  @Override
  public void updateBillingInterval(long orgId, String intervalDbValue) {
    jdbc.update(
        "UPDATE organizations SET billing_interval = ?::billing_interval_t, updated_at = NOW()"
            + " WHERE id = ?",
        new Object[] {intervalDbValue, orgId},
        new int[] {Types.OTHER, Types.BIGINT});
    jdbc.update(
        "UPDATE users SET billing_interval = ?::billing_interval_t WHERE id = "
            + primaryOwnerSubquery(),
        new Object[] {intervalDbValue, orgId},
        new int[] {Types.OTHER, Types.BIGINT});
  }

  @Override
  public boolean openPaymentGrace(long orgId, Instant graceUntil) {
    int rows =
        jdbc.update(
            "UPDATE organizations SET payment_grace_until = ?, updated_at = NOW()"
                + " WHERE id = ? AND (payment_grace_until IS NULL OR payment_grace_until < NOW())",
            new Object[] {Timestamp.from(graceUntil), orgId},
            new int[] {Types.TIMESTAMP, Types.BIGINT});
    if (rows == 1) {
      jdbc.update(
          "UPDATE users SET payment_grace_until = ? WHERE id = " + primaryOwnerSubquery(),
          new Object[] {Timestamp.from(graceUntil), orgId},
          new int[] {Types.TIMESTAMP, Types.BIGINT});
    }
    return rows == 1;
  }

  @Override
  public void clearPaymentGrace(long orgId) {
    jdbc.update(
        "UPDATE organizations SET payment_grace_until = NULL, updated_at = NOW() WHERE id = ?",
        orgId);
    jdbc.update(
        "UPDATE users SET payment_grace_until = NULL WHERE id = " + primaryOwnerSubquery(), orgId);
  }

  /**
   * SQL fragment that resolves to the "primary owner" user_id of {@code ?} (the org id). The
   * tiebreak rule matches {@link JdbcOrgPlanRepository}: highest current plan tier wins, ties
   * broken by earliest membership. Inlined as a subquery so each dual-write stays a single
   * statement — the alternative was two round-trips per Stripe event, which add up under load.
   */
  private static String primaryOwnerSubquery() {
    return """
        (SELECT m.user_id
           FROM org_members m
           JOIN users u ON u.id = m.user_id
          WHERE m.org_id = ? AND m.role = 'owner'::org_role
          ORDER BY CASE u.plan
                     WHEN 'enterprise' THEN 5
                     WHEN 'business'   THEN 4
                     WHEN 'pro'        THEN 3
                     WHEN 'starter'    THEN 2
                     WHEN 'free'       THEN 1
                     ELSE 0
                   END DESC,
                   m.added_at ASC
          LIMIT 1)
        """;
  }
}
