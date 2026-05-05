package org.arguslog.api.adapter.out.postgres;

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
