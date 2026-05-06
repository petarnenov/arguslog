package org.arguslog.worker.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.arguslog.worker.application.port.AlertContextResolver;
import org.arguslog.worker.application.port.AlertContextResolver.Resolved;
import org.arguslog.worker.domain.PersistedEvent;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class JdbcAlertContextResolverTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static AlertContextResolver resolver;

  @BeforeAll
  static void boot() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Flyway.configure()
        .dataSource(dataSource)
        .locations(resolveMigrationsLocation())
        .load()
        .migrate();
    seed(dataSource);
    resolver = new JdbcAlertContextResolver(dataSource);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @Test
  void resolvesOrgProjectAndIssueTitleInOneShot() {
    Resolved r = resolver.resolve(event(7L, 101L)).orElseThrow();
    assertThat(r.orgSlug()).isEqualTo("acme");
    assertThat(r.projectSlug()).isEqualTo("web");
    assertThat(r.issueTitle()).isEqualTo("TypeError: x");
  }

  @Test
  void unknownIssueReturnsEmpty() {
    assertThat(resolver.resolve(event(9999L, 101L))).isEmpty();
  }

  @Test
  void issueProjectMismatchReturnsEmpty() {
    // Issue 7 belongs to project 101; asking against 102 must not silently leak
    // across projects.
    assertThat(resolver.resolve(event(7L, 102L))).isEmpty();
  }

  private static PersistedEvent event(long issueId, long projectId) {
    Instant now = Instant.parse("2026-05-05T12:00:00Z");
    return new PersistedEvent(issueId, projectId, "error", false, 1, now, now);
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
        .orElseThrow(() -> new IllegalStateException("Cannot locate api migrations"));
  }

  private static void seed(DataSource ds) throws Exception {
    try (Connection conn = ds.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (101, 1, 'web', 'Web', 'js')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (102, 1, 'api', 'API', 'java')");
      exec(
          conn,
          "INSERT INTO issues (id, project_id, fingerprint, title, culprit, level, first_seen_at,"
              + " last_seen_at, occurrence_count) VALUES"
              + " (7, 101, 'fp1', 'TypeError: x', 'render at app.js:42', 'error', NOW(), NOW(), 1)");
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
