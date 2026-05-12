package org.arguslog.worker.tier.adapter.out.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.arguslog.worker.tier.application.port.TierExpiryRepository;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTierExpiryRepository implements TierExpiryRepository {

  // V30+: drop the user back to regular and clear grant metadata atomically. The CTE returns
  // the affected user ids; the outer SELECT resolves their owned org ids so callers can keep
  // the pre-OSS audit / alert shape (per-org event for each affected user).
  private static final String DOWNGRADE_SQL =
      """
      WITH downgraded AS (
        UPDATE users
           SET tier             = 'regular'::org_tier,
               tier_expires_at  = NULL,
               tier_granted_by  = NULL,
               tier_granted_at  = NULL,
               tier_reason      = NULL
         WHERE tier != 'regular'::org_tier
           AND tier_expires_at IS NOT NULL
           AND tier_expires_at < ?
        RETURNING id
      )
      SELECT DISTINCT m.org_id
        FROM downgraded d
        JOIN org_members m ON m.user_id = d.id AND m.role = 'owner'::org_role
      """;

  private final DataSource dataSource;

  public JdbcTierExpiryRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public List<Long> downgradeExpiredTiers(Instant now) {
    List<Long> orgIds = new ArrayList<>();
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(DOWNGRADE_SQL)) {
      stmt.setObject(1, Timestamp.from(now), Types.TIMESTAMP);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) orgIds.add(rs.getLong(1));
      }
    } catch (SQLException e) {
      // Intentionally no timestamp in the message — Arguslog fingerprints by exception value
      // and a mutable Instant would splay every daily run into a fresh "issue".
      throw new RuntimeException("Tier expiry downgrade failed", e);
    }
    return orgIds;
  }
}
