package org.arguslog.ingest.adapter.out.postgres;

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
import org.arguslog.billing.PlanTier;
import org.arguslog.ingest.application.port.ProjectQuotaContext;
import org.arguslog.ingest.application.port.ProjectQuotaContext.Context;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class JdbcProjectQuotaContextTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static ProjectQuotaContext repository;

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
    repository = new JdbcProjectQuotaContext(dataSource);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @Test
  void resolvesProjectToOrgAndPlan() {
    Optional<Context> ctx = repository.lookup(101L);
    assertThat(ctx).isPresent();
    assertThat(ctx.orElseThrow().orgId()).isEqualTo(1L);
    assertThat(ctx.orElseThrow().plan()).isEqualTo(PlanTier.REGULAR);
  }

  @Test
  void unknownProjectReturnsEmpty() {
    assertThat(repository.lookup(9999L)).isEmpty();
  }

  @Test
  void cacheReturnsSameInstanceWithinTtl() {
    Optional<Context> a = repository.lookup(101L);
    Optional<Context> b = repository.lookup(101L);
    // Caffeine returns the cached Optional reference identically.
    assertThat(a).isSameAs(b);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

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
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
