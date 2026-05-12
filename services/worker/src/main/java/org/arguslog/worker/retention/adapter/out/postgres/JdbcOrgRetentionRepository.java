package org.arguslog.worker.retention.adapter.out.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.arguslog.billing.PlanTier;
import org.arguslog.worker.retention.application.port.OrgRetentionRepository;
import org.arguslog.worker.retention.domain.OrgRetention;
import org.springframework.stereotype.Repository;

/**
 * Reads each org's owner tier (V30+: {@code users.tier}) + {@code retention_days_override} and
 * computes effective retention in Java. Tier→duration mapping lives in {@link PlanTier} so it stays
 * in one place (the SQL stays free of CASE statements that would drift from the enum).
 *
 * <p>Filtered in-memory rather than in SQL because org counts are small (one row per tenant) and
 * the alternative would duplicate the tier defaults across SQL + Java.
 */
@Repository
public class JdbcOrgRetentionRepository implements OrgRetentionRepository {

  // Resolve each org's effective tier by JOIN-ing through its primary owner (highest-tier +
  // earliest-membership tiebreak, same picker the rest of the codebase uses). Ownerless orgs
  // (rare orphans) default to REGULAR via the COALESCE.
  private static final String SELECT_ALL =
      """
      SELECT o.id,
             COALESCE(ou.tier::text, 'regular') AS tier,
             o.retention_days_override
        FROM organizations o
        LEFT JOIN LATERAL (
          SELECT m.user_id
            FROM org_members m
            JOIN users u ON u.id = m.user_id
           WHERE m.org_id = o.id AND m.role = 'owner'::org_role
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
      """;

  private final DataSource dataSource;

  public JdbcOrgRetentionRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public List<OrgRetention> orgsBelowFloor(Duration floor) {
    List<OrgRetention> out = new ArrayList<>();
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(SELECT_ALL);
        ResultSet rs = stmt.executeQuery()) {
      while (rs.next()) {
        long orgId = rs.getLong(1);
        PlanTier tier = PlanTier.fromDbValue(rs.getString(2));
        int overrideDays = rs.getInt(3);
        boolean hasOverride = !rs.wasNull();
        Duration effective = hasOverride ? Duration.ofDays(overrideDays) : tier.retention();
        if (effective.compareTo(floor) < 0) {
          out.add(new OrgRetention(orgId, effective));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to read org retention", e);
    }
    return out;
  }
}
