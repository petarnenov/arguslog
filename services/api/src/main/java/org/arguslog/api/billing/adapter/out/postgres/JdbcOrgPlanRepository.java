package org.arguslog.api.billing.adapter.out.postgres;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.billing.domain.BillingInterval;
import org.arguslog.api.billing.domain.PlanTier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcOrgPlanRepository implements OrgPlanRepository {

  private final JdbcTemplate jdbc;

  public JdbcOrgPlanRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Optional<PlanTier> findPlan(long orgId) {
    try {
      String raw =
          jdbc.queryForObject(
              "SELECT plan::text FROM organizations WHERE id = ?", String.class, orgId);
      return Optional.of(PlanTier.fromDbValue(raw));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<PlanTier> findHighestPlanForOwner(UUID userId) {
    // Tier ordinal climbs FREE → STARTER → PRO → BUSINESS → ENTERPRISE, so MAX over the dbValue
    // (mapped to ordinal in app code) gives the user's highest tier across owner-orgs. Done in
    // Java instead of SQL because the enum's dbValue is the only PG-known representation; pulling
    // every dbValue and computing max in code is a handful of strings — cheap.
    String[] raws =
        jdbc.query(
                """
                SELECT o.plan::text AS plan
                  FROM organizations o
                  JOIN org_members m ON m.org_id = o.id
                 WHERE m.user_id = ?
                   AND m.role = 'owner'::org_role
                """,
                (rs, rowNum) -> rs.getString("plan"),
                userId)
            .toArray(new String[0]);
    if (raws.length == 0) return Optional.empty();
    PlanTier best = PlanTier.FREE;
    for (String raw : raws) {
      PlanTier t = PlanTier.fromDbValue(raw);
      if (t.ordinal() > best.ordinal()) best = t;
    }
    return Optional.of(best);
  }

  @Override
  public Optional<Instant> findPaymentGraceUntil(long orgId) {
    try {
      Timestamp ts =
          jdbc.queryForObject(
              "SELECT payment_grace_until FROM organizations WHERE id = ?", Timestamp.class, orgId);
      return Optional.ofNullable(ts).map(Timestamp::toInstant);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<BillingInterval> findBillingInterval(long orgId) {
    try {
      String raw =
          jdbc.queryForObject(
              "SELECT billing_interval::text FROM organizations WHERE id = ?", String.class, orgId);
      return Optional.of(BillingInterval.fromDbValue(raw));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<Instant> findRenewsAt(long orgId) {
    try {
      Timestamp ts =
          jdbc.queryForObject(
              "SELECT plan_renews_at FROM organizations WHERE id = ?", Timestamp.class, orgId);
      return Optional.ofNullable(ts).map(Timestamp::toInstant);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<BonusSnapshot> findActiveBonus(long orgId) {
    try {
      return Optional.ofNullable(
          jdbc.queryForObject(
              """
              SELECT o.bonus_until,
                     o.bonus_reason,
                     gb.email AS granted_by_email
                FROM organizations o
                LEFT JOIN users gb ON gb.id = o.bonus_granted_by
               WHERE o.id = ?
                 AND o.bonus_until IS NOT NULL
                 AND o.bonus_until > NOW()
              """,
              (rs, rowNum) -> {
                Timestamp ts = rs.getTimestamp("bonus_until");
                return new BonusSnapshot(
                    ts == null ? null : ts.toInstant(),
                    rs.getString("bonus_reason"),
                    rs.getString("granted_by_email"));
              },
              orgId));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }
}
