package org.arguslog.api.tier.adapter.out.postgres;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.tier.application.port.TierLookupRepository;
import org.arguslog.billing.PlanTier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcTierLookupRepository implements TierLookupRepository {

  private final JdbcTemplate jdbc;

  public JdbcTierLookupRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Optional<PlanTier> findTier(long orgId) {
    // Multi-owner orgs inherit the most generous owner's tier. Ownerless orgs (rare orphans)
    // return empty so callers fall back to PlanTier.REGULAR explicitly.
    String[] raws =
        jdbc.query(
                """
                SELECT u.tier::text AS tier
                  FROM org_members m
                  JOIN users u ON u.id = m.user_id
                 WHERE m.org_id = ? AND m.role = 'owner'::org_role
                """,
                (rs, rowNum) -> rs.getString("tier"),
                orgId)
            .toArray(new String[0]);
    if (raws.length == 0) return Optional.empty();
    PlanTier best = PlanTier.REGULAR;
    for (String raw : raws) {
      PlanTier t = PlanTier.fromDbValue(raw);
      if (t.ordinal() > best.ordinal()) best = t;
    }
    return Optional.of(best);
  }

  @Override
  public Optional<PlanTier> findTierForUser(UUID userId) {
    try {
      String raw =
          jdbc.queryForObject("SELECT tier::text FROM users WHERE id = ?", String.class, userId);
      return Optional.of(PlanTier.fromDbValue(raw));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<TierGrantSnapshot> findActiveTierGrant(UUID userId) {
    try {
      return Optional.ofNullable(
          jdbc.queryForObject(
              """
              SELECT u.tier_expires_at,
                     u.tier_reason,
                     gb.email AS granted_by_email
                FROM users u
                LEFT JOIN users gb ON gb.id = u.tier_granted_by
               WHERE u.id = ?
                 AND u.tier_expires_at IS NOT NULL
                 AND u.tier_expires_at > NOW()
              """,
              (rs, rowNum) -> {
                Timestamp ts = rs.getTimestamp("tier_expires_at");
                Instant expiresAt = ts == null ? null : ts.toInstant();
                return new TierGrantSnapshot(
                    expiresAt, rs.getString("tier_reason"), rs.getString("granted_by_email"));
              },
              userId));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }
}
