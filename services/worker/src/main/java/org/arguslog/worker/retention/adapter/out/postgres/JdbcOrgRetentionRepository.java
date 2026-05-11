package org.arguslog.worker.retention.adapter.out.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.arguslog.worker.retention.application.port.OrgRetentionRepository;
import org.arguslog.worker.retention.domain.OrgRetention;
import org.arguslog.worker.retention.domain.WorkerPlanTier;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code organizations.plan} + {@code retention_days_override} and computes effective
 * retention in Java. Plan→duration mapping lives in {@link WorkerPlanTier} so it stays in one place
 * (the SQL stays free of CASE statements that would drift from the enum).
 *
 * <p>Filtered in-memory rather than in SQL because org counts are small (one row per tenant) and
 * the alternative would duplicate the plan defaults across SQL + Java.
 */
@Repository
public class JdbcOrgRetentionRepository implements OrgRetentionRepository {

  // V27+: organizations.plan dropped, plan now lives on users. Resolve each org's effective
  // plan by JOIN-ing through its primary owner (highest-tier + earliest-membership tiebreak,
  // same picker the rest of the codebase uses). Ownerless orgs (rare orphans) default to FREE.
  private static final String SELECT_ALL =
      """
      SELECT o.id,
             COALESCE(ou.plan::text, 'free') AS plan,
             o.retention_days_override
        FROM organizations o
        LEFT JOIN LATERAL (
          SELECT m.user_id
            FROM org_members m
            JOIN users u ON u.id = m.user_id
           WHERE m.org_id = o.id AND m.role = 'owner'::org_role
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
        WorkerPlanTier tier = WorkerPlanTier.fromDbValue(rs.getString(2));
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
