package org.arguslog.api.adapter.out.postgres;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.application.port.UserRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcUserRepository implements UserRepository {

  private final JdbcTemplate jdbc;

  public JdbcUserRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public void upsertFromJwt(UUID id, String email, String displayName) {
    // 1. Common path — user already known under this JWT sub. UPDATE-by-PK keeps the row in sync
    //    with whatever Keycloak now reports (email rename, display-name change). Survives the case
    //    where Keycloak admin rewrites the user's email between sessions, since we don't depend on
    //    email-stability to identify them.
    int updated =
        jdbc.update(
            "UPDATE users SET email = ?, display_name = ?, last_seen_at = NOW() WHERE id = ?",
            email,
            displayName,
            id);
    if (updated > 0) return;

    // 2. No row for this sub. If a stale row exists for this email (Keycloak realm was reseeded
    //    and the sub rotated, OR a placeholder was pre-created by an org invite), realign its id
    //    to the new sub. V6's ON UPDATE CASCADE on org_members / project_members /
    //    personal_access_tokens carries the memberships over so the user keeps their orgs.
    List<UUID> staleIds =
        jdbc.queryForList(
            "SELECT id FROM users WHERE email = ? AND id <> ?", UUID.class, email, id);
    if (!staleIds.isEmpty()) {
      jdbc.update(
          "UPDATE users SET id = ?, display_name = ?, last_seen_at = NOW() WHERE email = ?",
          id,
          displayName,
          email);
      return;
    }

    // 3. Genuinely first-time login. INSERT with ON CONFLICT (id) DO NOTHING in case a concurrent
    //    request beats us to it (cheap insurance — the matching row will be picked up next call).
    jdbc.update(
        """
        INSERT INTO users (id, email, display_name, last_seen_at)
        VALUES (?, ?, ?, NOW())
        ON CONFLICT (id) DO NOTHING
        """,
        id,
        email,
        displayName);
  }

  @Override
  public Optional<UUID> findIdByEmail(String email) {
    try {
      String id =
          jdbc.queryForObject("SELECT id::text FROM users WHERE email = ?", String.class, email);
      return Optional.ofNullable(id).map(UUID::fromString);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public UUID createPlaceholder(String email) {
    UUID id = UUID.randomUUID();
    // No last_seen_at — placeholder hasn't logged in yet. display_name stays null until
    // upsertFromJwt
    // realigns this row on first login.
    jdbc.update(
        "INSERT INTO users (id, email, display_name, last_seen_at) VALUES (?, ?, NULL, NULL)",
        id,
        email);
    return id;
  }
}
