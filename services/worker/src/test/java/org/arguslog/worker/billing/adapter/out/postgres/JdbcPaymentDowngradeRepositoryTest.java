package org.arguslog.worker.billing.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.arguslog.worker.billing.application.port.PaymentDowngradeRepository;
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
class JdbcPaymentDowngradeRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static PaymentDowngradeRepository repo;

  @BeforeAll
  static void boot() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Flyway.configure().dataSource(dataSource).locations(resolveMigrations()).load().migrate();
    repo = new JdbcPaymentDowngradeRepository(dataSource);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void truncate() {
    new org.springframework.jdbc.core.JdbcTemplate(dataSource)
        .execute("TRUNCATE organizations RESTART IDENTITY CASCADE");
  }

  @Test
  void downgradesProOrgWithExpiredGrace() throws Exception {
    seedOrg(1L, "pro", Instant.parse("2026-05-01T00:00:00Z"));

    List<Long> ids = repo.downgradeExpired(Instant.parse("2026-05-13T04:00:00Z"));

    assertThat(ids).containsExactly(1L);
    assertOrgState(1L, "free", null);
  }

  @Test
  void leavesProOrgWithStillFutureGraceAlone() throws Exception {
    Instant future = Instant.parse("2026-05-20T00:00:00Z");
    seedOrg(1L, "pro", future);

    List<Long> ids = repo.downgradeExpired(Instant.parse("2026-05-13T04:00:00Z"));

    assertThat(ids).isEmpty();
    assertOrgState(1L, "pro", future);
  }

  @Test
  void leavesProOrgWithoutGraceAlone() throws Exception {
    seedOrg(1L, "pro", null);

    List<Long> ids = repo.downgradeExpired(Instant.parse("2026-05-13T04:00:00Z"));

    assertThat(ids).isEmpty();
    assertOrgState(1L, "pro", null);
  }

  @Test
  void leavesAlreadyFreeOrgAloneEvenIfGraceWasOpenedWindowAgo() throws Exception {
    // Sequence: payment failed, grace opened, then subscription deleted webhook flipped plan
    // to FREE before the worker ran. Worker must not "downgrade" an already-Free row and emit
    // noisy audit events.
    seedOrg(1L, "free", Instant.parse("2026-05-01T00:00:00Z"));

    List<Long> ids = repo.downgradeExpired(Instant.parse("2026-05-13T04:00:00Z"));

    assertThat(ids).isEmpty();
  }

  @Test
  void downgradesMultipleOrgsAtomically() throws Exception {
    seedOrg(1L, "pro", Instant.parse("2026-04-30T00:00:00Z"));
    seedOrg(2L, "pro", Instant.parse("2026-05-22T00:00:00Z")); // future — survives
    seedOrg(3L, "pro", Instant.parse("2026-05-01T00:00:00Z"));
    seedOrg(4L, "free", Instant.parse("2026-04-01T00:00:00Z"));

    List<Long> ids = repo.downgradeExpired(Instant.parse("2026-05-13T04:00:00Z"));

    assertThat(ids).containsExactlyInAnyOrder(1L, 3L);
    assertOrgState(2L, "pro", Instant.parse("2026-05-22T00:00:00Z"));
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static void seedOrg(long id, String plan, Instant graceUntil) throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO organizations (id, slug, name, plan, payment_grace_until)"
                    + " VALUES (?, ?, ?, ?::org_plan, ?)")) {
      stmt.setLong(1, id);
      stmt.setString(2, "org-" + id);
      stmt.setString(3, "Org " + id);
      stmt.setString(4, plan);
      if (graceUntil == null) stmt.setNull(5, java.sql.Types.TIMESTAMP);
      else stmt.setTimestamp(5, Timestamp.from(graceUntil));
      stmt.execute();
    }
  }

  private static void assertOrgState(long id, String expectedPlan, Instant expectedGrace)
      throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "SELECT plan::text, payment_grace_until FROM organizations WHERE id = ?")) {
      stmt.setLong(1, id);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        assertThat(rs.getString(1)).isEqualTo(expectedPlan);
        Timestamp ts = rs.getTimestamp(2);
        if (expectedGrace == null) {
          assertThat(ts).isNull();
        } else {
          assertThat(ts.toInstant()).isEqualTo(expectedGrace);
        }
      }
    }
  }

  private static String resolveMigrations() {
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
}
