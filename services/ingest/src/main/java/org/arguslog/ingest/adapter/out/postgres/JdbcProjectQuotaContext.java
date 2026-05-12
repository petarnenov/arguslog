package org.arguslog.ingest.adapter.out.postgres;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.billing.PlanTier;
import org.arguslog.ingest.application.port.ProjectQuotaContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Caffeine-cached resolution of {@code (projectId → orgId, planTier)}. The tier column changes
 * after admin grants; the 5-minute TTL bounds how long an over-the-cap project keeps getting served
 * after a downgrade. A 5-minute lag on monthly quota is acceptable — admin-driven downgrades are
 * rare and the cap is a soft signal anyway.
 */
@Component
public class JdbcProjectQuotaContext implements ProjectQuotaContext {

  // V30+: resolve the tier via the project's org's primary owner (highest tier + earliest
  // membership tiebreak, same picker the rest of the codebase uses). Caffeine TTL bounds the
  // cap-staleness window after an admin grant / expiry flips the user tier.
  private static final String SQL =
      """
      SELECT p.org_id,
             COALESCE(ou.tier::text, 'regular') AS tier_text
        FROM projects p
        LEFT JOIN LATERAL (
          SELECT m.user_id
            FROM org_members m
            JOIN users u ON u.id = m.user_id
           WHERE m.org_id = p.org_id AND m.role = 'owner'::org_role
           ORDER BY CASE u.tier
                      WHEN 'platinum' THEN 4
                      WHEN 'gold'     THEN 3
                      WHEN 'silver'   THEN 2
                      WHEN 'regular'  THEN 1
                      ELSE 0
                    END DESC,
                    m.added_at ASC
           LIMIT 1
        ) AS owner ON TRUE
        LEFT JOIN users ou ON ou.id = owner.user_id
       WHERE p.id = ?
      """;

  private final JdbcTemplate jdbc;
  private final Cache<Long, Optional<Context>> cache;

  public JdbcProjectQuotaContext(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.cache =
        Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(5)).build();
  }

  @Override
  public Optional<Context> lookup(long projectId) {
    Optional<Context> cached = cache.getIfPresent(projectId);
    if (cached != null) return cached;
    Optional<Context> fresh = loadFromDb(projectId);
    cache.put(projectId, fresh);
    return fresh;
  }

  private Optional<Context> loadFromDb(long projectId) {
    try {
      Context row =
          jdbc.queryForObject(
              SQL,
              (rs, rowNum) ->
                  new Context(
                      rs.getLong("org_id"), PlanTier.fromDbValue(rs.getString("tier_text"))),
              projectId);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }
}
