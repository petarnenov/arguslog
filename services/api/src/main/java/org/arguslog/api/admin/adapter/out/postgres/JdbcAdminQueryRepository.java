package org.arguslog.api.admin.adapter.out.postgres;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.admin.application.port.AdminQueryPort;
import org.arguslog.api.admin.domain.AdminAuditEntry;
import org.arguslog.api.admin.domain.AdminOrgRow;
import org.arguslog.api.admin.domain.AdminStats;
import org.arguslog.api.admin.domain.AdminUserRow;
import org.arguslog.api.admin.domain.BonusGrant;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAdminQueryRepository implements AdminQueryPort {

  private final JdbcTemplate jdbc;

  public JdbcAdminQueryRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public AdminStats stats() {
    long users = countOrZero("SELECT COUNT(*) FROM users");
    long orgs = countOrZero("SELECT COUNT(*) FROM organizations");
    long projects = countOrZero("SELECT COUNT(*) FROM projects WHERE archived_at IS NULL");
    long issues = countOrZero("SELECT COUNT(*) FROM issues");
    long grants =
        countOrZero(
            "SELECT COUNT(*) FROM users WHERE tier_expires_at IS NOT NULL AND tier_expires_at > NOW()");

    Map<String, Long> byTier = new HashMap<>();
    jdbc.query(
        """
        SELECT effective_tier AS tier, COUNT(*) AS n
          FROM (
            SELECT DISTINCT ON (o.id)
                   o.id,
                   COALESCE(u.tier::text, 'regular') AS effective_tier
              FROM organizations o
              LEFT JOIN org_members m ON m.org_id = o.id AND m.role = 'owner'::org_role
              LEFT JOIN users u ON u.id = m.user_id
             ORDER BY o.id,
                      CASE u.tier
                        WHEN 'platinum' THEN 4
                        WHEN 'gold'     THEN 3
                        WHEN 'silver'   THEN 2
                        WHEN 'regular'  THEN 1
                        ELSE 0
                      END DESC NULLS LAST,
                      m.added_at ASC NULLS LAST
          ) AS effective
         GROUP BY effective_tier
        """,
        rs -> {
          byTier.put(rs.getString("tier"), rs.getLong("n"));
        });

    long e7 = sumEvents(7);
    long e30 = sumEvents(30);
    return new AdminStats(users, orgs, projects, issues, byTier, grants, e7, e30);
  }

  private long sumEvents(int days) {
    try {
      Long n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM events WHERE received_at > NOW() - (? || ' days')::interval",
              Long.class,
              String.valueOf(days));
      return n == null ? 0L : n;
    } catch (Exception e) {
      return 0L;
    }
  }

  private long countOrZero(String sql) {
    try {
      Long n = jdbc.queryForObject(sql, Long.class);
      return n == null ? 0 : n;
    } catch (Exception e) {
      return 0L;
    }
  }

  @Override
  public List<AdminUserRow> listUsers(String search, int offset, int limit) {
    String like = toLikeOrNull(search);
    return jdbc.query(
        """
        SELECT
          u.id              AS user_id,
          u.email           AS email,
          u.display_name    AS display_name,
          u.created_at      AS created_at,
          (SELECT COUNT(*) FROM org_members m WHERE m.user_id = u.id AND m.role = 'owner') AS owned,
          (SELECT COUNT(*) FROM org_members m WHERE m.user_id = u.id AND m.role <> 'owner') AS membered,
          u.tier::text AS tier
        FROM users u
        WHERE (?::text IS NULL
               OR LOWER(u.email) LIKE ?
               OR LOWER(COALESCE(u.display_name, '')) LIKE ?)
        ORDER BY u.created_at DESC
        LIMIT ? OFFSET ?
        """,
        (rs, rowNum) ->
            new AdminUserRow(
                (UUID) rs.getObject("user_id"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getInt("owned"),
                rs.getInt("membered"),
                rs.getString("tier")),
        like,
        like,
        like,
        limit,
        offset);
  }

  @Override
  public long countUsers(String search) {
    String like = toLikeOrNull(search);
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
              FROM users u
             WHERE (?::text IS NULL
                    OR LOWER(u.email) LIKE ?
                    OR LOWER(COALESCE(u.display_name, '')) LIKE ?)
            """,
            Long.class,
            like,
            like,
            like);
    return n == null ? 0 : n;
  }

  @Override
  public List<AdminOrgRow> listOrgs(String search, int offset, int limit) {
    String like = toLikeOrNull(search);
    return jdbc.query(orgQuery(true, true), this::mapOrgRow, like, like, like, limit, offset);
  }

  @Override
  public long countOrgs(String search) {
    String like = toLikeOrNull(search);
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
              FROM organizations o
             WHERE (?::text IS NULL
                    OR LOWER(o.slug) LIKE ?
                    OR LOWER(o.name) LIKE ?)
            """,
            Long.class,
            like,
            like,
            like);
    return n == null ? 0 : n;
  }

  @Override
  public Optional<AdminOrgRow> getOrg(long orgId) {
    try {
      AdminOrgRow row = jdbc.queryForObject(orgQuery(false, false), this::mapOrgRow, orgId);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private String orgQuery(boolean withSearch, boolean withPaging) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        """
        SELECT
          o.id                       AS org_id,
          o.slug                     AS slug,
          o.name                     AS name,
          ou.tier::text              AS tier,
          o.created_at               AS created_at,
          owner.user_id              AS owner_id,
          ou.email                   AS owner_email,
          (SELECT COUNT(*) FROM projects p WHERE p.org_id = o.id AND p.archived_at IS NULL) AS projects,
          (SELECT COUNT(*) FROM org_members m WHERE m.org_id = o.id) AS members,
          COALESCE((
            SELECT COUNT(*)::bigint
              FROM events e
              JOIN projects p ON p.id = e.project_id
             WHERE p.org_id = o.id
               AND e.received_at > NOW() - INTERVAL '30 days'
          ), 0) AS events30d,
          ou.tier_expires_at         AS tier_expires_at,
          ou.tier_reason             AS tier_reason,
          (SELECT bu.email FROM users bu WHERE bu.id = ou.tier_granted_by) AS tier_granted_by_email
        FROM organizations o
        LEFT JOIN LATERAL (
          SELECT m.user_id
            FROM org_members m
            JOIN users u ON u.id = m.user_id
           WHERE m.org_id = o.id AND m.role = 'owner'
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
        """);
    if (withSearch) {
      sb.append(" WHERE (?::text IS NULL OR LOWER(o.slug) LIKE ? OR LOWER(o.name) LIKE ?)\n");
      sb.append(" ORDER BY o.created_at DESC\n");
    } else {
      sb.append(" WHERE o.id = ?\n");
    }
    if (withPaging) {
      sb.append(" LIMIT ? OFFSET ?\n");
    }
    return sb.toString();
  }

  private AdminOrgRow mapOrgRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new AdminOrgRow(
        rs.getLong("org_id"),
        rs.getString("slug"),
        rs.getString("name"),
        rs.getString("tier"),
        rs.getTimestamp("created_at").toInstant(),
        (UUID) rs.getObject("owner_id"),
        rs.getString("owner_email"),
        rs.getInt("projects"),
        rs.getInt("members"),
        rs.getLong("events30d"),
        toInstant(rs, "tier_expires_at"),
        rs.getString("tier_reason"),
        rs.getString("tier_granted_by_email"));
  }

  private static Instant toInstant(java.sql.ResultSet rs, String column)
      throws java.sql.SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }

  @Override
  public void recordUserGrant(
      UUID userId, String tier, Instant until, UUID grantedBy, String reason) {
    jdbc.update(
        """
        UPDATE users
           SET tier             = ?::org_tier,
               tier_expires_at  = ?,
               tier_granted_by  = ?,
               tier_granted_at  = NOW(),
               tier_reason      = ?
         WHERE id = ?
        """,
        tier.toLowerCase(Locale.ROOT),
        until == null ? null : Timestamp.from(until),
        grantedBy,
        reason,
        userId);
  }

  @Override
  public void revokeUserGrant(UUID userId) {
    jdbc.update(
        """
        UPDATE users
           SET tier             = 'regular'::org_tier,
               tier_expires_at  = NULL,
               tier_granted_by  = NULL,
               tier_granted_at  = NULL,
               tier_reason      = NULL
         WHERE id = ?
        """,
        userId);
  }

  @Override
  public Optional<BonusGrant> findActiveUserBonus(UUID userId) {
    try {
      return Optional.ofNullable(
          jdbc.queryForObject(
              """
              SELECT u.tier_expires_at,
                     u.tier_granted_at,
                     u.tier_granted_by,
                     gb.email AS granted_by_email,
                     u.tier_reason
                FROM users u
                LEFT JOIN users gb ON gb.id = u.tier_granted_by
               WHERE u.id = ?
                 AND u.tier_expires_at IS NOT NULL
                 AND u.tier_expires_at > NOW()
              """,
              (rs, rowNum) ->
                  new BonusGrant(
                      toInstant(rs, "tier_expires_at"),
                      toInstant(rs, "tier_granted_at"),
                      (UUID) rs.getObject("tier_granted_by"),
                      rs.getString("granted_by_email"),
                      rs.getString("tier_reason")),
              userId));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public List<AdminAuditEntry> listAudit(int offset, int limit) {
    return jdbc.query(
        """
        SELECT id, ts, admin_user, admin_email, action, target_type, target_id, payload::text AS payload
          FROM admin_audit_log
         ORDER BY ts DESC
         LIMIT ? OFFSET ?
        """,
        (rs, rowNum) ->
            new AdminAuditEntry(
                rs.getLong("id"),
                rs.getObject("ts", OffsetDateTime.class).toInstant(),
                (UUID) rs.getObject("admin_user"),
                rs.getString("admin_email"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("payload")),
        limit,
        offset);
  }

  @Override
  public long countAudit() {
    Long n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log", Long.class);
    return n == null ? 0 : n;
  }

  @Override
  public void writeAudit(
      UUID adminUser,
      String adminEmail,
      String action,
      String targetType,
      String targetId,
      String payloadJson) {
    jdbc.update(
        """
        INSERT INTO admin_audit_log (admin_user, admin_email, action, target_type, target_id, payload)
        VALUES (?, ?, ?, ?, ?, ?::jsonb)
        """,
        adminUser,
        adminEmail,
        action,
        targetType,
        targetId,
        payloadJson == null ? "{}" : payloadJson);
  }

  private static String toLikeOrNull(String search) {
    if (search == null) return null;
    String trimmed = search.trim().toLowerCase(Locale.ROOT);
    if (trimmed.isEmpty()) return null;
    return "%" + trimmed.replace("%", "\\%").replace("_", "\\_") + "%";
  }
}
