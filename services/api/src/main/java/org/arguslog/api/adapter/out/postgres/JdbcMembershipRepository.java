package org.arguslog.api.adapter.out.postgres;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.MembershipWriteRepository;
import org.arguslog.api.domain.Member;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcMembershipRepository implements MembershipRepository, MembershipWriteRepository {

  private final JdbcTemplate jdbc;

  public JdbcMembershipRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public boolean userIsMemberOfOrg(UUID userId, long orgId) {
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM org_members WHERE user_id = ? AND org_id = ?)",
            Boolean.class,
            userId,
            orgId);
    return Boolean.TRUE.equals(exists);
  }

  @Override
  public Optional<String> userRoleInOrg(UUID userId, long orgId) {
    try {
      String role =
          jdbc.queryForObject(
              "SELECT role::text FROM org_members WHERE user_id = ? AND org_id = ?",
              String.class,
              userId,
              orgId);
      return Optional.ofNullable(role);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public List<Member> listMembersOf(long orgId) {
    return jdbc.query(
        """
        SELECT u.id AS user_id,
               u.email,
               u.display_name,
               m.role::text AS role,
               m.added_at,
               (u.last_seen_at IS NULL) AS pending
          FROM org_members m
          JOIN users u ON u.id = m.user_id
         WHERE m.org_id = ?
         ORDER BY m.added_at ASC
        """,
        (rs, rowNum) ->
            new Member(
                UUID.fromString(rs.getString("user_id")),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getTimestamp("added_at").toInstant(),
                rs.getBoolean("pending")),
        orgId);
  }

  @Override
  public int countOwnersOf(long orgId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM org_members WHERE org_id = ? AND role = 'owner'::org_role",
            Integer.class,
            orgId);
    return count == null ? 0 : count;
  }

  @Override
  public java.util.Optional<UUID> findPrimaryOwnerOfOrg(long orgId) {
    try {
      UUID id =
          jdbc.queryForObject(
              """
              SELECT m.user_id
                FROM org_members m
                JOIN users u ON u.id = m.user_id
               WHERE m.org_id = ? AND m.role = 'owner'::org_role
               ORDER BY CASE u.tier
                          WHEN 'platinum' THEN 4
                          WHEN 'gold'     THEN 3
                          WHEN 'silver'   THEN 2
                          WHEN 'regular'  THEN 1
                          ELSE 0
                        END DESC,
                        m.added_at ASC
               LIMIT 1
              """,
              UUID.class,
              orgId);
      return java.util.Optional.ofNullable(id);
    } catch (org.springframework.dao.EmptyResultDataAccessException e) {
      return java.util.Optional.empty();
    }
  }

  @Override
  public java.util.Optional<Long> findPrimaryOwnedOrg(UUID userId) {
    // OSS conversion (V30+): tier lives on users, not organizations — and a user's orgs all
    // inherit the same user-level tier. The legacy "highest org-plan first" tiebreak collapses
    // to a single dimension, so just pick the earliest-owned org.
    try {
      Long id =
          jdbc.queryForObject(
              """
              SELECT m.org_id
                FROM org_members m
               WHERE m.user_id = ? AND m.role = 'owner'::org_role
               ORDER BY m.added_at ASC
               LIMIT 1
              """,
              Long.class,
              userId);
      return java.util.Optional.ofNullable(id);
    } catch (org.springframework.dao.EmptyResultDataAccessException e) {
      return java.util.Optional.empty();
    }
  }

  @Override
  public boolean addMember(long orgId, UUID userId, String role) {
    return jdbc.update(
            """
            INSERT INTO org_members (org_id, user_id, role)
            VALUES (?, ?, ?::org_role)
            ON CONFLICT (org_id, user_id) DO NOTHING
            """,
            orgId,
            userId,
            role)
        > 0;
  }

  @Override
  public boolean updateRole(long orgId, UUID userId, String role) {
    return jdbc.update(
            "UPDATE org_members SET role = ?::org_role WHERE org_id = ? AND user_id = ?",
            role,
            orgId,
            userId)
        > 0;
  }

  @Override
  public boolean removeMember(long orgId, UUID userId) {
    return jdbc.update("DELETE FROM org_members WHERE org_id = ? AND user_id = ?", orgId, userId)
        > 0;
  }
}
