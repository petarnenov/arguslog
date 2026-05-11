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
    try {
      Org org =
          jdbc.queryForObject(
              """
              SELECT id, slug, name, plan::text AS plan, created_at
                FROM organizations
               WHERE id = ?
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
    return jdbc.query(
        """
        SELECT o.id, o.slug, o.name, o.plan::text AS plan, o.created_at
          FROM organizations o
          JOIN org_members m ON m.org_id = o.id
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
