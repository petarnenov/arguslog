package org.arguslog.api.billing.adapter.out.postgres;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-user billing repository (V27+). All writes target {@code users} rows directly — the legacy
 * {@code organizations.stripe_customer_id / plan / plan_renews_at / billing_interval /
 * payment_grace_until} columns were dropped in V27. Org-keyed read methods are kept for callers
 * that still hand us an {@code orgId} (Stripe checkout for example knows about the org from
 * client_reference_id); under the hood they resolve the org's primary owner and read the user row,
 * matching the picker rule the rest of the codebase uses.
 */
@Component
public class JdbcBillingCustomerRepository implements BillingCustomerRepository {

  private final JdbcTemplate jdbc;

  public JdbcBillingCustomerRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Optional<String> findCustomerId(long orgId) {
    return queryStringForOwner(orgId, "stripe_customer_id").filter(s -> !s.isBlank());
  }

  @Override
  public Optional<String> findCustomerIdForUser(UUID userId) {
    try {
      String id =
          jdbc.queryForObject(
              "SELECT stripe_customer_id FROM users WHERE id = ?", String.class, userId);
      return Optional.ofNullable(id).filter(s -> !s.isBlank());
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<Long> findOrgIdByCustomerId(String customerId) {
    // V27+: the customer id lives only on users now. Resolve the user, then surface their
    // primary owned org so legacy callers that key on orgId keep working.
    try {
      Long id =
          jdbc.queryForObject(
              """
              SELECT m.org_id
                FROM users u
                JOIN org_members m ON m.user_id = u.id AND m.role = 'owner'::org_role
                JOIN organizations o ON o.id = m.org_id
               WHERE u.stripe_customer_id = ?
               ORDER BY CASE u.plan
                          WHEN 'enterprise' THEN 5
                          WHEN 'business'   THEN 4
                          WHEN 'pro'        THEN 3
                          WHEN 'starter'    THEN 2
                          WHEN 'free'       THEN 1
                          ELSE 0
                        END DESC,
                        m.added_at ASC
               LIMIT 1
              """,
              Long.class,
              customerId);
      return Optional.ofNullable(id);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<UUID> findUserIdByCustomerId(String customerId) {
    try {
      UUID id =
          jdbc.queryForObject(
              "SELECT id FROM users WHERE stripe_customer_id = ?", UUID.class, customerId);
      return Optional.ofNullable(id);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public void saveCustomerId(long orgId, String customerId) {
    // V27+ user-primary: writes land on the org's primary owner. The orgId argument is the
    // historical surface (checkout knows about orgs); the row that actually changes is the user's.
    jdbc.update(
        "UPDATE users SET stripe_customer_id = ? WHERE id = " + primaryOwnerSubquery(),
        customerId,
        orgId);
  }

  @Override
  public void updatePlanAndRenewal(long orgId, String planDbValue, Instant renewsAt) {
    jdbc.update(
        "UPDATE users SET plan = ?::org_plan, plan_renews_at = ? WHERE id = "
            + primaryOwnerSubquery(),
        new Object[] {planDbValue, renewsAt == null ? null : Timestamp.from(renewsAt), orgId},
        new int[] {Types.OTHER, Types.TIMESTAMP, Types.BIGINT});
  }

  @Override
  public void updateBillingInterval(long orgId, String intervalDbValue) {
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
            "UPDATE users SET payment_grace_until = ? WHERE id = "
                + primaryOwnerSubquery()
                + " AND (payment_grace_until IS NULL OR payment_grace_until < NOW())",
            new Object[] {Timestamp.from(graceUntil), orgId},
            new int[] {Types.TIMESTAMP, Types.BIGINT});
    return rows == 1;
  }

  @Override
  public void clearPaymentGrace(long orgId) {
    jdbc.update(
        "UPDATE users SET payment_grace_until = NULL WHERE id = " + primaryOwnerSubquery(), orgId);
  }

  private Optional<String> queryStringForOwner(long orgId, String column) {
    try {
      String value =
          jdbc.queryForObject(
              "SELECT u." + column + " FROM users u WHERE u.id = " + primaryOwnerSubquery(),
              String.class,
              orgId);
      return Optional.ofNullable(value);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  /**
   * SQL fragment that resolves to the "primary owner" user_id of {@code ?} (the org id). The
   * tiebreak rule matches {@link JdbcOrgPlanRepository}: highest current plan tier wins, ties
   * broken by earliest membership. Inlined as a subquery so each write stays a single statement —
   * the alternative was two round-trips per Stripe event, which add up under load.
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
