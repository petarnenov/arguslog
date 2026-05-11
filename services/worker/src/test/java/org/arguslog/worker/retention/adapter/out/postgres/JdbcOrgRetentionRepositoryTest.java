package org.arguslog.worker.retention.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import org.arguslog.worker.retention.application.port.OrgRetentionRepository;
import org.arguslog.worker.retention.domain.OrgRetention;
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
class JdbcOrgRetentionRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static OrgRetentionRepository repo;

  @BeforeAll
  static void boot() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Flyway.configure().dataSource(dataSource).locations(resolveMigrations()).load().migrate();
    repo = new JdbcOrgRetentionRepository(dataSource);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void truncate() throws Exception {
    new org.springframework.jdbc.core.JdbcTemplate(dataSource)
        .execute("TRUNCATE organizations RESTART IDENTITY CASCADE");
    new org.springframework.jdbc.core.JdbcTemplate(dataSource)
        .execute("DELETE FROM users WHERE email LIKE 'retention-test-%'");
  }

  @Test
  void freePlanOrgIsBelowOneYearFloor() throws Exception {
    insertOrg(1L, "free", null);

    List<OrgRetention> below = repo.orgsBelowFloor(Duration.ofDays(365));

    assertThat(below).containsExactly(new OrgRetention(1L, Duration.ofDays(30)));
  }

  @Test
  void enterprisePlanIsAtFloorAndExcluded() throws Exception {
    insertOrg(1L, "enterprise", null);

    List<OrgRetention> below = repo.orgsBelowFloor(Duration.ofDays(365));

    assertThat(below).isEmpty();
  }

  @Test
  void overrideShortenedBelowDefaultIsHonored() throws Exception {
    insertOrg(1L, "enterprise", 60);

    List<OrgRetention> below = repo.orgsBelowFloor(Duration.ofDays(365));

    assertThat(below).containsExactly(new OrgRetention(1L, Duration.ofDays(60)));
  }

  @Test
  void overrideAtFloorIsExcluded() throws Exception {
    insertOrg(1L, "free", 365);

    List<OrgRetention> below = repo.orgsBelowFloor(Duration.ofDays(365));

    assertThat(below).isEmpty();
  }

  @Test
  void mixedOrgListReturnsOnlyThoseBelowFloor() throws Exception {
    insertOrg(1L, "free", null);
    insertOrg(2L, "pro", null);
    insertOrg(3L, "enterprise", null);
    insertOrg(4L, "enterprise", 90);

    List<OrgRetention> below = repo.orgsBelowFloor(Duration.ofDays(365));

    assertThat(below)
        .containsExactlyInAnyOrder(
            new OrgRetention(1L, Duration.ofDays(30)),
            new OrgRetention(2L, Duration.ofDays(90)),
            new OrgRetention(4L, Duration.ofDays(90)));
  }

  private static void insertOrg(long id, String plan, Integer overrideDays) throws Exception {
    // V27+: org.plan dropped — seed a user with that plan and own the org so the JOIN through
    // the primary owner returns the desired effective tier.
    java.util.UUID owner = new java.util.UUID(0L, id);
    try (Connection conn = dataSource.getConnection()) {
      try (PreparedStatement stmt =
          conn.prepareStatement(
              "INSERT INTO users (id, email, display_name, plan)"
                  + " VALUES (?, ?, ?, ?::org_plan)")) {
        stmt.setObject(1, owner);
        stmt.setString(2, "retention-test-" + id + "@example.com");
        stmt.setString(3, "owner-" + id);
        stmt.setString(4, plan);
        stmt.execute();
      }
      try (PreparedStatement stmt =
          conn.prepareStatement(
              "INSERT INTO organizations (id, slug, name, retention_days_override)"
                  + " VALUES (?, ?, ?, ?)")) {
        stmt.setLong(1, id);
        stmt.setString(2, "org-" + id);
        stmt.setString(3, "Org " + id);
        if (overrideDays == null) stmt.setNull(4, java.sql.Types.INTEGER);
        else stmt.setInt(4, overrideDays);
        stmt.execute();
      }
      try (PreparedStatement stmt =
          conn.prepareStatement(
              "INSERT INTO org_members (org_id, user_id, role)"
                  + " VALUES (?, ?, 'owner'::org_role)")) {
        stmt.setLong(1, id);
        stmt.setObject(2, owner);
        stmt.execute();
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
