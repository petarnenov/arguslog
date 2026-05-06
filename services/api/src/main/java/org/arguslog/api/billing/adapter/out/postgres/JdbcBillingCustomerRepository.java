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
  }

  @Override
  public boolean openPaymentGrace(long orgId, Instant graceUntil) {
    int rows =
        jdbc.update(
            "UPDATE organizations SET payment_grace_until = ?, updated_at = NOW()"
                + " WHERE id = ? AND (payment_grace_until IS NULL OR payment_grace_until < NOW())",
            new Object[] {Timestamp.from(graceUntil), orgId},
            new int[] {Types.TIMESTAMP, Types.BIGINT});
    return rows == 1;
  }

  @Override
  public void clearPaymentGrace(long orgId) {
    jdbc.update(
        "UPDATE organizations SET payment_grace_until = NULL, updated_at = NOW() WHERE id = ?",
        orgId);
  }
}
