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
    // V27+: bonus / plan distribution live on users. The "orgs by plan" overview now reflects
    // the org-effective-tier (max of owners' user.plan); for a single-owner org this is the
    // owner's tier, for multi-owner it's the max. Bonus count is the number of users with an
    // active grant — that's what platform admins actually want to track.
    long bonus =
        countOrZero(
            "SELECT COUNT(*) FROM users WHERE bonus_until IS NOT NULL AND bonus_until > NOW()");

    Map<String, Long> byPlan = new HashMap<>();
    jdbc.query(
        """
        SELECT effective_plan AS plan, COUNT(*) AS n
          FROM (
            SELECT DISTINCT ON (o.id)
                   o.id,
                   COALESCE(u.plan::text, 'free') AS effective_plan
              FROM organizations o
              LEFT JOIN org_members m ON m.org_id = o.id AND m.role = 'owner'::org_role
              LEFT JOIN users u ON u.id = m.user_id
             ORDER BY o.id,
                      CASE u.plan
                        WHEN 'enterprise' THEN 5
                        WHEN 'business'   THEN 4
                        WHEN 'pro'        THEN 3
                        WHEN 'starter'    THEN 2
                        WHEN 'free'       THEN 1
                        ELSE 0
                      END DESC NULLS LAST,
                      m.added_at ASC NULLS LAST
          ) AS effective
         GROUP BY effective_plan
        """,
        rs -> {
          byPlan.put(rs.getString("plan"), rs.getLong("n"));
        });

    long e7 = sumEvents(7);
    long e30 = sumEvents(30);
    return new AdminStats(users, orgs, projects, issues, byPlan, bonus, e7, e30);
  }

  /**
   * Sum of events ingested over the last N days. Pulled from {@code events} via the {@code
   * received_at} TIMESTAMPTZ column. Returns 0 when the table is empty / unreachable — never throws
   * into the admin response.
   */
  private long sumEvents(int days) {
    try {
      Long n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM events WHERE received_at > NOW() - (? || ' days')::interval",
              Long.class,
              String.valueOf(days));
      return n == null ? 0L : n;
    } catch (Exception e) {
      // events table partition might be missing in fresh dev DBs — degrade gracefully.
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
          u.plan::text AS highest_plan
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
                rs.getString("highest_plan")),
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
    // The search WHERE clause has three `?` placeholders (the NULL guard plus the two LIKE
    // operands), then LIMIT and OFFSET — five binds total. Forgetting the first `like` was
    // the bug behind admin/orgs returning 5xx with "No value specified for parameter 5" while
    // countOrgs (which only needs three) silently kept working.
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
    // Per-user billing (V26+): plan / renews / bonus / grace come from the org's primary
    // owner's user row, not the org row itself. Tiebreaker for multi-owner is "highest tier
    // first, then earliest membership" — same rule as JdbcOrgPlanRepository so the admin table
    // and runtime cap-checks always agree on which owner is "primary".
    sb.append(
        """
        SELECT
          o.id                   AS org_id,
          o.slug                 AS slug,
          o.name                 AS name,
          ou.plan::text          AS plan,
          o.created_at           AS created_at,
          owner.user_id          AS owner_id,
          ou.email               AS owner_email,
          (SELECT COUNT(*) FROM projects p WHERE p.org_id = o.id AND p.archived_at IS NULL) AS projects,
          (SELECT COUNT(*) FROM org_members m WHERE m.org_id = o.id) AS members,
          COALESCE((
            SELECT COUNT(*)::bigint
              FROM events e
              JOIN projects p ON p.id = e.project_id
             WHERE p.org_id = o.id
               AND e.received_at > NOW() - INTERVAL '30 days'
          ), 0) AS events30d,
          ou.plan_renews_at      AS plan_renews_at,
          ou.bonus_until         AS bonus_until,
          ou.bonus_reason        AS bonus_reason,
          (SELECT bu.email FROM users bu WHERE bu.id = ou.bonus_granted_by) AS bonus_granted_by_email,
          ou.payment_grace_until AS payment_grace_until
        FROM organizations o
        LEFT JOIN LATERAL (
          SELECT m.user_id
            FROM org_members m
            JOIN users u ON u.id = m.user_id
           WHERE m.org_id = o.id AND m.role = 'owner'
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
        rs.getString("plan"),
        rs.getTimestamp("created_at").toInstant(),
        (UUID) rs.getObject("owner_id"),
        rs.getString("owner_email"),
        rs.getInt("projects"),
        rs.getInt("members"),
        rs.getLong("events30d"),
        toInstant(rs, "plan_renews_at"),
        toInstant(rs, "bonus_until"),
        rs.getString("bonus_reason"),
        rs.getString("bonus_granted_by_email"),
        toInstant(rs, "payment_grace_until"));
  }

  private static Instant toInstant(java.sql.ResultSet rs, String column)
      throws java.sql.SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }

  @Override
  public Optional<BonusGrant> findActiveBonus(long orgId) {
    // V27+: bonus columns dropped from organizations. Read from the org's primary owner —
    // same picker rule as everywhere else. Empty for ownerless orgs or owners with no grant.
    try {
      return Optional.ofNullable(
          jdbc.queryForObject(
              """
              SELECT u.bonus_until,
                     u.bonus_granted_at,
                     u.bonus_granted_by,
                     gb.email AS granted_by_email,
                     u.bonus_reason
                FROM org_members m
                JOIN users u ON u.id = m.user_id
                LEFT JOIN users gb ON gb.id = u.bonus_granted_by
               WHERE m.org_id = ?
                 AND m.role = 'owner'::org_role
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
              (rs, rowNum) ->
                  new BonusGrant(
                      toInstant(rs, "bonus_until"),
                      toInstant(rs, "bonus_granted_at"),
                      (UUID) rs.getObject("bonus_granted_by"),
                      rs.getString("granted_by_email"),
                      rs.getString("bonus_reason")),
              orgId));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public void recordGrant(long orgId, String plan, Instant until, UUID grantedBy, String reason) {
    // V27+: bonus + plan live on users only. The org-keyed grant endpoint is kept for legacy
    // callers but resolves the org's primary owner and writes the user row — same effect as the
    // direct per-user grant API.
    String planLc = plan.toLowerCase(Locale.ROOT);
    Timestamp untilTs = Timestamp.from(until);
    jdbc.update(
        """
        UPDATE users
           SET plan = ?::org_plan,
               bonus_until      = ?,
               bonus_granted_by = ?,
               bonus_granted_at = NOW(),
               bonus_reason     = ?
         WHERE id = (
           SELECT m.user_id
             FROM org_members m
             JOIN users u ON u.id = m.user_id
            WHERE m.org_id = ? AND m.role = 'owner'::org_role
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
         )
        """,
        planLc,
        untilTs,
        grantedBy,
        reason,
        orgId);
  }

  @Override
  public void revokeGrant(long orgId) {
    // V27+: revoke targets the org's primary owner's user row.
    jdbc.update(
        """
        UPDATE users
           SET plan = 'free'::org_plan,
               bonus_until      = NULL,
               bonus_granted_by = NULL,
               bonus_granted_at = NULL,
               bonus_reason     = NULL
         WHERE id = (
           SELECT m.user_id
             FROM org_members m
             JOIN users u ON u.id = m.user_id
            WHERE m.org_id = ? AND m.role = 'owner'::org_role
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
         )
        """,
        orgId);
  }

  @Override
  public void recordUserGrant(
      UUID userId, String plan, Instant until, UUID grantedBy, String reason) {
    // V27+: source of truth is users row, no org mirror needed.
    jdbc.update(
        """
        UPDATE users
           SET plan = ?::org_plan,
               bonus_until      = ?,
               bonus_granted_by = ?,
               bonus_granted_at = NOW(),
               bonus_reason     = ?
         WHERE id = ?
        """,
        plan.toLowerCase(Locale.ROOT),
        Timestamp.from(until),
        grantedBy,
        reason,
        userId);
  }

  @Override
  public void revokeUserGrant(UUID userId) {
    jdbc.update(
        """
        UPDATE users
           SET plan = 'free'::org_plan,
               bonus_until      = NULL,
               bonus_granted_by = NULL,
               bonus_granted_at = NULL,
               bonus_reason     = NULL
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
              SELECT u.bonus_until,
                     u.bonus_granted_at,
                     u.bonus_granted_by,
                     gb.email AS granted_by_email,
                     u.bonus_reason
                FROM users u
                LEFT JOIN users gb ON gb.id = u.bonus_granted_by
               WHERE u.id = ?
                 AND u.bonus_until IS NOT NULL
                 AND u.bonus_until > NOW()
              """,
              (rs, rowNum) ->
                  new BonusGrant(
                      toInstant(rs, "bonus_until"),
                      toInstant(rs, "bonus_granted_at"),
                      (UUID) rs.getObject("bonus_granted_by"),
                      rs.getString("granted_by_email"),
                      rs.getString("bonus_reason")),
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
