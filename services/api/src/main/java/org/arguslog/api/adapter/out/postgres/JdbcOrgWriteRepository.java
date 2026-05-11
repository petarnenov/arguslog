package org.arguslog.api.adapter.out.postgres;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.application.OrgUseCase.DuplicateOrgException;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.domain.Org;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcOrgWriteRepository implements OrgWriteRepository {

  private final JdbcTemplate jdbc;

  public JdbcOrgWriteRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Org create(String slug, String name, String planDbValue) {
    // ON CONFLICT DO NOTHING returns zero rows on collision instead of throwing — the surrounding
    // @Transactional in OrgService stays clean (a raised unique-violation would poison the tx with
    // "current transaction is aborted"). We surface a domain-level duplicate exception so the
    // controller can map it to a friendly 409 instead of leaking a 500.
    //
    // planDbValue carries the creator's highest active plan tier (GH #38) so a paying user who
    // spins up a side org gets the same coverage automatically. Renewal/billing identity is NOT
    // copied — the new org starts a fresh cycle on the same tier.
    Org inserted =
        jdbc.query(
            """
            INSERT INTO organizations (slug, name, plan) VALUES (?, ?, ?::org_plan)
            ON CONFLICT (slug) DO NOTHING
            RETURNING id, plan::text AS plan, created_at
            """,
            rs -> {
              if (!rs.next()) return null;
              return new Org(
                  rs.getLong("id"),
                  slug,
                  name,
                  rs.getString("plan"),
                  rs.getTimestamp("created_at").toInstant());
            },
            slug,
            name,
            planDbValue);
    if (inserted == null) {
      throw new DuplicateOrgException(
          "An organization with this name already exists. Please choose a different name.");
    }
    return inserted;
  }

  @Override
  public boolean delete(long orgId) {
    return jdbc.update("DELETE FROM organizations WHERE id = ?", orgId) > 0;
  }

  @Override
  public void addMember(long orgId, UUID userId, String role) {
    jdbc.update(
        """
        INSERT INTO org_members (org_id, user_id, role)
        VALUES (?, ?, ?::org_role)
        ON CONFLICT (org_id, user_id) DO NOTHING
        """,
        orgId,
        userId,
        role);
  }

  @Override
  public Optional<Org> findById(long orgId) {
    // V26+: the displayed plan is the org's primary-owner's user.plan, not the legacy o.plan.
    // Without this JOIN, a user's other orgs render their old FREE tier even after a grant
    // hits one of them — the visible bug behind the per-user billing rewrite.
    try {
      Org org =
          jdbc.queryForObject(
              """
              SELECT o.id, o.slug, o.name, COALESCE(ou.plan, o.plan)::text AS plan, o.created_at
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
               WHERE o.id = ?
              """,
              (rs, rowNum) ->
                  new Org(
                      rs.getLong("id"),
                      rs.getString("slug"),
                      rs.getString("name"),
                      rs.getString("plan"),
                      rs.getTimestamp("created_at").toInstant()),
              orgId);
      return Optional.ofNullable(org);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public int countOwnedBy(UUID userId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)::int
              FROM org_members
             WHERE user_id = ?
               AND role = 'owner'::org_role
            """,
            Integer.class,
            userId);
    return n == null ? 0 : n;
  }

  @Override
  public List<Org> listForUser(UUID userId) {
    // Plan column resolved via primary-owner's user.plan (V26+). The user's "My orgs" list and
    // every Org dropdown shows the effective tier regardless of which org was last billed.
    return jdbc.query(
        """
        SELECT o.id, o.slug, o.name, COALESCE(ou.plan, o.plan)::text AS plan, o.created_at
          FROM organizations o
          JOIN org_members m ON m.org_id = o.id
          LEFT JOIN LATERAL (
            SELECT mm.user_id
              FROM org_members mm
              JOIN users u ON u.id = mm.user_id
             WHERE mm.org_id = o.id AND mm.role = 'owner'::org_role
             ORDER BY CASE u.plan
                        WHEN 'enterprise' THEN 5
                        WHEN 'business'   THEN 4
                        WHEN 'pro'        THEN 3
                        WHEN 'starter'    THEN 2
                        WHEN 'free'       THEN 1
                        ELSE 0
                      END DESC,
                      mm.added_at ASC
             LIMIT 1
          ) AS owner ON TRUE
          LEFT JOIN users ou ON ou.id = owner.user_id
         WHERE m.user_id = ?
         ORDER BY o.slug ASC
        """,
        (rs, rowNum) ->
            new Org(
                rs.getLong("id"),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("plan"),
                rs.getTimestamp("created_at").toInstant()),
        userId);
  }
}
