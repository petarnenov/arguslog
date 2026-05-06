package org.arguslog.ingest.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.arguslog.ingest.application.port.MonthlyQuotaCounter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class JdbcMonthlyQuotaCounterTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static final LocalDate PERIOD = LocalDate.of(2026, 5, 1);

  private static HikariDataSource dataSource;
  private static MonthlyQuotaCounter counter;

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
    counter = new JdbcMonthlyQuotaCounter(dataSource);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void clean() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      exec(conn, "TRUNCATE quotas RESTART IDENTITY CASCADE");
    }
  }

  @Test
  void firstConsumeInsertsRowWithCountOne() throws Exception {
    boolean ok = counter.tryConsume(1L, PERIOD, 100L);
    assertThat(ok).isTrue();
    assertThat(currentCount(1L, PERIOD)).isEqualTo(1L);
  }

  @Test
  void subsequentConsumesIncrementUntilCap() {
    for (int i = 0; i < 5; i++) {
      assertThat(counter.tryConsume(1L, PERIOD, 5L)).isTrue();
    }
    // 6th consume — counter is at 5, cap is 5, WHERE quotas.events_count < 5 is false → empty
    // RETURNING.
    assertThat(counter.tryConsume(1L, PERIOD, 5L)).isFalse();
  }

  @Test
  void counterAtCapBlocksFurtherConsumesEvenAcrossSeparateCalls() throws Exception {
    counter.tryConsume(1L, PERIOD, 2L);
    counter.tryConsume(1L, PERIOD, 2L);
    assertThat(counter.tryConsume(1L, PERIOD, 2L)).isFalse();
    assertThat(currentCount(1L, PERIOD)).isEqualTo(2L);
  }

  @Test
  void differentOrgsHaveIndependentCounters() throws Exception {
    counter.tryConsume(1L, PERIOD, 5L);
    counter.tryConsume(2L, PERIOD, 5L);
    assertThat(currentCount(1L, PERIOD)).isEqualTo(1L);
    assertThat(currentCount(2L, PERIOD)).isEqualTo(1L);
  }

  @Test
  void differentPeriodsHaveIndependentCounters() throws Exception {
    counter.tryConsume(1L, LocalDate.of(2026, 4, 1), 5L);
    counter.tryConsume(1L, LocalDate.of(2026, 5, 1), 5L);
    assertThat(currentCount(1L, LocalDate.of(2026, 4, 1))).isEqualTo(1L);
    assertThat(currentCount(1L, LocalDate.of(2026, 5, 1))).isEqualTo(1L);
  }

  @Test
  void zeroCapAlwaysBlocks() {
    assertThat(counter.tryConsume(1L, PERIOD, 0L)).isFalse();
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static long currentCount(long orgId, LocalDate period) throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "SELECT events_count FROM quotas WHERE org_id = ? AND period_start = ?")) {
      stmt.setLong(1, orgId);
      stmt.setObject(2, period);
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    }
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
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (2, 'other', 'Other')");
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
