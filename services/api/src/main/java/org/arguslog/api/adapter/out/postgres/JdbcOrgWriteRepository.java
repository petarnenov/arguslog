package org.arguslog.api.adapter.out.postgres;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.domain.Org;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

@Component
public class JdbcOrgWriteRepository implements OrgWriteRepository {

  private static final int MAX_SLUG_ATTEMPTS = 50;

  private final JdbcTemplate jdbc;

  public JdbcOrgWriteRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Org create(String baseSlug, String name) {
    for (int attempt = 1; attempt <= MAX_SLUG_ATTEMPTS; attempt++) {
      String slug = attempt == 1 ? baseSlug : baseSlug + "-" + attempt;
      try {
        return insert(slug, name);
      } catch (DataIntegrityViolationException e) {
        // unique violation on slug — try the next suffix
      }
    }
    throw new IllegalStateException(
        "could not allocate a unique slug after "
            + MAX_SLUG_ATTEMPTS
            + " attempts for "
            + baseSlug);
  }

  private Org insert(String slug, String name) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var ps =
              connection.prepareStatement(
                  "INSERT INTO organizations (slug, name) VALUES (?, ?) RETURNING id, plan, created_at",
                  new String[] {"id", "plan", "created_at"});
          ps.setString(1, slug);
          ps.setString(2, name);
          return ps;
        },
        keys);
    var row = keys.getKeys();
    if (row == null) {
      throw new IllegalStateException("INSERT returned no keys");
    }
    long id = ((Number) row.get("id")).longValue();
    String plan = String.valueOf(row.get("plan"));
    Timestamp createdAt = (Timestamp) row.get("created_at");
    return new Org(id, slug, name, plan, createdAt.toInstant());
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
