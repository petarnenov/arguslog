package org.arguslog.ingest.adapter.out.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.ingest.application.port.ProjectAuthenticator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PostgresProjectAuthenticatorTest {

  private static final DockerImageName TIMESCALE_IMAGE =
      DockerImageName.parse("timescale/timescaledb:latest-pg16")
          .asCompatibleSubstituteFor("postgres");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TIMESCALE_IMAGE)
          .withDatabaseName("argus")
          .withUsername("argus")
          .withPassword("argus");

  private static HikariDataSource dataSource;
  private static ProjectAuthenticator auth;

  @BeforeAll
  static void migrateAndSeed() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);

    // Migrations live in the api service (single owner). For tests we point
    // Flyway at api's source tree directly to avoid duplicating SQL into
    // every consumer service. Resolved relative to the gradle test cwd
    // (services/ingest/), with a small fallback for IDE runs that use the
    // repo root.
    Flyway.configure()
        .dataSource(dataSource)
        .locations(resolveMigrationsLocation())
        .load()
        .migrate();

    seed(dataSource);
    auth = new PostgresProjectAuthenticator(dataSource);
  }

  @AfterAll
  static void tearDown() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @Test
  void resolvesProjectIdForActivePublicOnlyKey() {
    Optional<Long> result = auth.authenticate(101L, "public-key-active");
    assertThat(result).contains(101L);
  }

  @Test
  void rejectsWhenProjectIdDoesNotMatchKey() {
    Optional<Long> result = auth.authenticate(999L, "public-key-active");
    assertThat(result).isEmpty();
  }

  @Test
  void rejectsInactiveKey() {
    Optional<Long> result = auth.authenticate(101L, "public-key-inactive");
    assertThat(result).isEmpty();
  }

  @Test
  void rejectsKeyThatRequiresASecret() {
    Optional<Long> result = auth.authenticate(101L, "public-key-with-secret");
    assertThat(result).isEmpty();
  }

  @Test
  void rejectsUnknownKey() {
    Optional<Long> result = auth.authenticate(101L, "no-such-key");
    assertThat(result).isEmpty();
  }

  @Test
  void rejectsBlankKey() {
    assertThat(auth.authenticate(101L, "")).isEmpty();
    assertThat(auth.authenticate(101L, "   ")).isEmpty();
    assertThat(auth.authenticate(101L, null)).isEmpty();
  }

  private static String resolveMigrationsLocation() {
    List<Path> candidates =
        List.of(
            Path.of("../api/src/main/resources/db/migration"),
            Path.of("services/api/src/main/resources/db/migration"));
    return candidates.stream()
        .map(Path::toAbsolutePath)
        .filter(Files::isDirectory)
        .findFirst()
        .map(p -> "filesystem:" + p)
        .orElseThrow(
            () -> new IllegalStateException("Cannot locate api migrations. Tried: " + candidates));
  }

  /** Inserts an organization, project (id 101), and three project_keys covering the cases. */
  private static void seed(DataSource ds) throws Exception {
    try (Connection conn = ds.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme Inc.')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (101, 1, 'web', 'Web', 'javascript')");
      exec(
          conn,
          "INSERT INTO project_keys (project_id, dsn_public, dsn_secret_hash, active) VALUES (101, 'public-key-active', NULL, TRUE)");
      exec(
          conn,
          "INSERT INTO project_keys (project_id, dsn_public, dsn_secret_hash, active) VALUES (101, 'public-key-inactive', NULL, FALSE)");
      exec(
          conn,
          "INSERT INTO project_keys (project_id, dsn_public, dsn_secret_hash, active) VALUES (101, 'public-key-with-secret', '$argon2id$placeholder', TRUE)");
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
