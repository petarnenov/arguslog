package org.arguslog.api.adapter.out.postgres;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.application.port.UserRepository;
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
    // The JWT subject is the canonical identity. If an older row exists for this email
    // under a different id (e.g. the Keycloak realm was reseeded and the user's sub
    // rotated), realign the stored id rather than letting the unique-on-email constraint
    // abort the whole transaction with a bare 500.
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
    jdbc.update(
        """
        INSERT INTO users (id, email, display_name, last_seen_at)
        VALUES (?, ?, ?, NOW())
        ON CONFLICT (id) DO UPDATE
          SET email = EXCLUDED.email,
              display_name = EXCLUDED.display_name,
              last_seen_at = NOW()
        """,
        id,
        email,
        displayName);
  }
}
