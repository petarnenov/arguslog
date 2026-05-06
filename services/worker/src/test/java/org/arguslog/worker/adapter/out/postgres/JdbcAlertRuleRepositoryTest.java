package org.arguslog.worker.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import javax.sql.DataSource;
import org.arguslog.worker.application.port.AlertRuleRepository;
import org.arguslog.worker.domain.AlertRule;
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
class JdbcAlertRuleRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
      DockerImageName.parse("timescale/timescaledb:latest-pg16")
          .asCompatibleSubstituteFor("postgres"))
      .withDatabaseName("arguslog")
      .withUsername("arguslog")
      .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static AlertRuleRepository repository;

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
    repository = new JdbcAlertRuleRepository(dataSource, new ObjectMapper());
  }

  @AfterAll
  static void stop() {
    if (dataSource != null)
      dataSource.close();
  }

  @BeforeEach
  void clean() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      exec(conn, "TRUNCATE alert_rules RESTART IDENTITY CASCADE");
    }
  }

  @Test
  void returnsOnlyEnabledRulesForTheProject() throws Exception {
    insertRule(101L, "fires", "{\"level\":{\"in\":[\"error\"]}}", true);
    insertRule(101L, "skipped-because-disabled", "{}", false);
    insertRule(102L, "wrong-project", "{}", true);

    List<AlertRule> rules = repository.enabledForProject(101L);

    assertThat(rules).hasSize(1);
    assertThat(rules.get(0).conditions().path("level").path("in").get(0).asText())
        .isEqualTo("error");
  }

  @Test
  void roundTripsConditionsAndActionsJson() throws Exception {
    insertRule(101L, "rich", "{\"firstSeenWindow\":\"PT5M\",\"occurrenceThreshold\":42}", true);
    AlertRule loaded = repository.enabledForProject(101L).get(0);
    assertThat(loaded.conditions().path("firstSeenWindow").asText()).isEqualTo("PT5M");
    assertThat(loaded.conditions().path("occurrenceThreshold").asInt()).isEqualTo(42);
    assertThat(loaded.actions().path("destinationIds").get(0).asInt()).isEqualTo(1);
  }

  @Test
  void emptyForProjectWithNoRules() {
    assertThat(repository.enabledForProject(101L)).isEmpty();
  }

  private static String resolveMigrationsLocation() {
    List<Path> candidates = List.of(
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
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (101, 1, 'web', 'Web', 'js')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (102, 2, 'web', 'Web', 'js')");
    }
  }

  private static void insertRule(long projectId, String name, String conditions, boolean enabled)
      throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO alert_rules (project_id, name, conditions, actions, throttle_seconds, enabled)"
                + " VALUES (?, ?, ?::jsonb, '{\"destinationIds\":[1]}'::jsonb, 300, ?)")) {
      stmt.setLong(1, projectId);
      stmt.setString(2, name);
      stmt.setString(3, conditions);
      stmt.setBoolean(4, enabled);
      stmt.execute();
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
