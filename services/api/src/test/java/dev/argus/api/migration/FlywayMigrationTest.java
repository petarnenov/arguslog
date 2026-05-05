package dev.argus.api.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Locks in the V1 schema as a contract: every migration in
 * services/api/src/main/resources/db/migration must apply cleanly to a fresh TimescaleDB instance,
 * and the headline tables/extensions must end up wired. This is the single gate that catches "I
 * broke the schema" PRs in CI.
 */
@Testcontainers
class FlywayMigrationTest {

  private static final DockerImageName TIMESCALE_IMAGE =
      DockerImageName.parse("timescale/timescaledb:latest-pg16")
          .asCompatibleSubstituteFor("postgres");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TIMESCALE_IMAGE)
          .withDatabaseName("argus")
          .withUsername("argus")
          .withPassword("argus");

  @Test
  void appliesCleanlyAndIsIdempotent() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load();

    MigrateResult first = flyway.migrate();
    assertThat(first.success).isTrue();
    assertThat(first.migrationsExecuted).isGreaterThan(0);

    MigrateResult second = flyway.migrate();
    assertThat(second.success).isTrue();
    assertThat(second.migrationsExecuted).isZero();

    try (Connection conn =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      assertThat(extensions(conn)).contains("timescaledb", "pgcrypto", "citext");
      assertThat(tables(conn))
          .contains(
              "users",
              "organizations",
              "org_members",
              "projects",
              "project_keys",
              "environments",
              "project_members",
              "releases",
              "source_map_artifacts",
              "issues",
              "events",
              "alert_destinations",
              "alert_rules",
              "audit_log",
              "quotas",
              "pii_rules");
      assertThat(hypertables(conn)).contains("events", "audit_log");
      assertThat(rlsEnabledTables(conn))
          .contains("projects", "issues", "alert_rules", "alert_destinations", "quotas");
    }
  }

  private static Set<String> extensions(Connection conn) throws Exception {
    return queryColumn(conn, "SELECT extname FROM pg_extension");
  }

  private static Set<String> tables(Connection conn) throws Exception {
    return queryColumn(
        conn,
        "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename NOT LIKE 'flyway_%'");
  }

  private static Set<String> hypertables(Connection conn) throws Exception {
    return queryColumn(conn, "SELECT hypertable_name FROM timescaledb_information.hypertables");
  }

  private static Set<String> rlsEnabledTables(Connection conn) throws Exception {
    return queryColumn(
        conn, "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND rowsecurity = true");
  }

  private static Set<String> queryColumn(Connection conn, String sql) throws Exception {
    Set<String> out = new HashSet<>();
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }
}
