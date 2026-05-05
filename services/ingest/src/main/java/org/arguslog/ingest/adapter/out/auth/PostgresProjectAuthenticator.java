package org.arguslog.ingest.adapter.out.auth;

import org.arguslog.ingest.application.port.ProjectAuthenticator;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Verifies a public DSN against the {@code project_keys} table owned by the api service.
 *
 * <p>Currently only public-only DSNs (where {@code dsn_secret_hash IS NULL}) are accepted —
 * matching Sentry's browser-SDK contract where the public key is the entire credential. Backend
 * SDKs that carry a secret will be added in a follow-up by extending {@link
 * org.arguslog.ingest.application.IngestEventUseCase.Command} with a {@code dsnSecret} field and
 * verifying with argon2.
 *
 * <p>P4 will front this with a Caffeine cache (TTL ~30s) since the lookup runs on every event.
 */
@Component
public class PostgresProjectAuthenticator implements ProjectAuthenticator {

  private static final String LOOKUP_SQL =
      """
      SELECT project_id
        FROM project_keys
       WHERE dsn_public = ?
         AND project_id = ?
         AND active = TRUE
         AND dsn_secret_hash IS NULL
      """;

  private final JdbcTemplate jdbc;

  public PostgresProjectAuthenticator(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Optional<Long> authenticate(long projectId, String dsnPublicKey) {
    if (dsnPublicKey == null || dsnPublicKey.isBlank()) {
      return Optional.empty();
    }
    try {
      Long resolved = jdbc.queryForObject(LOOKUP_SQL, Long.class, dsnPublicKey, projectId);
      return Optional.ofNullable(resolved);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }
}
