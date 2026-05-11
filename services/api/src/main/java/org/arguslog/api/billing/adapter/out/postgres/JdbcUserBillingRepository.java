package org.arguslog.api.billing.adapter.out.postgres;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.billing.application.port.OrgPlanRepository.BonusSnapshot;
import org.arguslog.api.billing.application.port.UserBillingRepository;
import org.arguslog.api.billing.domain.BillingInterval;
import org.arguslog.billing.PlanTier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcUserBillingRepository implements UserBillingRepository {

  private final JdbcTemplate jdbc;

  public JdbcUserBillingRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Optional<PlanTier> findPlan(UUID userId) {
    try {
      String raw =
          jdbc.queryForObject(
              "SELECT plan::text FROM users WHERE id = ?", String.class, userId);
      return Optional.of(PlanTier.fromDbValue(raw));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<Instant> findPaymentGraceUntil(UUID userId) {
    try {
      Timestamp ts =
          jdbc.queryForObject(
              "SELECT payment_grace_until FROM users WHERE id = ?", Timestamp.class, userId);
      return Optional.ofNullable(ts).map(Timestamp::toInstant);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<BillingInterval> findBillingInterval(UUID userId) {
    try {
      String raw =
          jdbc.queryForObject(
              "SELECT billing_interval::text FROM users WHERE id = ?", String.class, userId);
      return Optional.of(BillingInterval.fromDbValue(raw));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<Instant> findRenewsAt(UUID userId) {
    try {
      Timestamp ts =
          jdbc.queryForObject(
              "SELECT plan_renews_at FROM users WHERE id = ?", Timestamp.class, userId);
      return Optional.ofNullable(ts).map(Timestamp::toInstant);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<BonusSnapshot> findActiveBonus(UUID userId) {
    try {
      return Optional.ofNullable(
          jdbc.queryForObject(
              """
              SELECT u.bonus_until,
                     u.bonus_reason,
                     gb.email AS granted_by_email
                FROM users u
                LEFT JOIN users gb ON gb.id = u.bonus_granted_by
               WHERE u.id = ?
                 AND u.bonus_until IS NOT NULL
                 AND u.bonus_until > NOW()
              """,
              (rs, rowNum) -> {
                Timestamp ts = rs.getTimestamp("bonus_until");
                return new BonusSnapshot(
                    ts == null ? null : ts.toInstant(),
                    rs.getString("bonus_reason"),
                    rs.getString("granted_by_email"));
              },
              userId));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }
}
