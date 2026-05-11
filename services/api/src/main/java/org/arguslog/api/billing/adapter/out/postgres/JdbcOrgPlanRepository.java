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
    // Per-user billing (V27+): the org's effective plan is the MAX tier across its owners'
    // user.plan. Multi-owner orgs inherit the most generous owner's tier. Ownerless orgs (rare
    // orphans) return empty so callers can fall back to FREE explicitly.
    String[] raws =
        jdbc.query(
                """
                SELECT u.plan::text AS plan
                  FROM org_members m
                  JOIN users u ON u.id = m.user_id
                 WHERE m.org_id = ? AND m.role = 'owner'::org_role
                """,
                (rs, rowNum) -> rs.getString("plan"),
                orgId)
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
  public Optional<PlanTier> findHighestPlanForOwner(UUID userId) {
    // Post-V26 this is a direct read of users.plan; no need to JOIN through orgs anymore since
    // the user's tier IS the source of truth, not derived from orgs.
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
  public Optional<Instant> findPaymentGraceUntil(long orgId) {
    Timestamp ts = ownerBilling(orgId, "payment_grace_until");
    return Optional.ofNullable(ts).map(Timestamp::toInstant);
  }

  @Override
  public Optional<BillingInterval> findBillingInterval(long orgId) {
    Object raw = ownerBilling(orgId, "billing_interval::text");
    return raw == null ? Optional.empty() : Optional.of(BillingInterval.fromDbValue((String) raw));
  }

  @Override
  public Optional<Instant> findRenewsAt(long orgId) {
    Timestamp ts = ownerBilling(orgId, "plan_renews_at");
    return Optional.ofNullable(ts).map(Timestamp::toInstant);
  }

  @Override
  public Optional<BonusSnapshot> findActiveBonus(long orgId) {
    // Resolve the highest-plan owner — ties broken by earliest membership so we have a stable
    // "primary owner" choice. Bonus metadata is attached to a user row, not an org row, post-V26.
    try {
      return Optional.ofNullable(
          jdbc.queryForObject(
              """
              SELECT u.bonus_until,
                     u.bonus_reason,
                     gb.email AS granted_by_email
                FROM org_members m
                JOIN users u ON u.id = m.user_id
                LEFT JOIN users gb ON gb.id = u.bonus_granted_by
               WHERE m.org_id = ? AND m.role = 'owner'::org_role
                 AND u.bonus_until IS NOT NULL
                 AND u.bonus_until > NOW()
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

  /**
   * Returns one column from the "primary owner" of {@code orgId} — the highest-tier owner with the
   * earliest membership as the deterministic tiebreaker. Returns null when the org has no owners
   * (rare orphan). Column expression is interpolated, so callers must hardcode it — never accept
   * untrusted input here.
   */
  private <T> T ownerBilling(long orgId, String columnExpr) {
    String sql =
        "SELECT "
            + columnExpr
            + " FROM org_members m"
            + " JOIN users u ON u.id = m.user_id"
            + " WHERE m.org_id = ? AND m.role = 'owner'::org_role"
            + " ORDER BY CASE u.plan"
            + "            WHEN 'enterprise' THEN 5"
            + "            WHEN 'business'   THEN 4"
            + "            WHEN 'pro'        THEN 3"
            + "            WHEN 'starter'    THEN 2"
            + "            WHEN 'free'       THEN 1"
            + "            ELSE 0"
            + "          END DESC,"
            + "          m.added_at ASC"
            + " LIMIT 1";
    try {
      return jdbc.queryForObject(
          sql,
          (rs, rowNum) -> {
            @SuppressWarnings("unchecked")
            T v = (T) rs.getObject(1);
            return v;
          },
          orgId);
    } catch (EmptyResultDataAccessException e) {
      return null;
    }
  }
}
