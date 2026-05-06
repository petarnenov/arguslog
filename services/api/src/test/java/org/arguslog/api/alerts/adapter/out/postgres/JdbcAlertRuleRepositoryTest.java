package org.arguslog.api.alerts.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.alerts.application.port.AlertRuleRepository;
import org.arguslog.api.alerts.domain.AlertRule;
import org.arguslog.api.security.OrgContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class JdbcAlertRuleRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static AlertRuleRepository repository;
  private static ObjectMapper mapper;

  @BeforeAll
  static void boot() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    seed(dataSource);
    mapper = new ObjectMapper();

    TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    JdbcAlertRuleRepository raw = new JdbcAlertRuleRepository(dataSource, mapper);
    repository =
        new AlertRuleRepository() {
          @Override
          public AlertRule create(
              long projectId,
              String name,
              JsonNode conditions,
              JsonNode actions,
              int throttleSeconds,
              boolean enabled) {
            return tx.execute(
                s -> raw.create(projectId, name, conditions, actions, throttleSeconds, enabled));
          }

          @Override
          public List<AlertRule> listForProject(long projectId) {
            return tx.execute(s -> raw.listForProject(projectId));
          }

          @Override
          public Optional<AlertRule> find(long projectId, long id) {
            return tx.execute(s -> raw.find(projectId, id));
          }

          @Override
          public Optional<AlertRule> update(
              long projectId,
              long id,
              String name,
              JsonNode conditions,
              JsonNode actions,
              int throttleSeconds,
              boolean enabled) {
            return tx.execute(
                s ->
                    raw.update(projectId, id, name, conditions, actions, throttleSeconds, enabled));
          }

          @Override
          public boolean delete(long projectId, long id) {
            return Boolean.TRUE.equals(tx.execute(s -> raw.delete(projectId, id)));
          }
        };
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void prime() throws Exception {
    OrgContext.set(1L);
    try (Connection conn = dataSource.getConnection()) {
      exec(conn, "TRUNCATE alert_rules RESTART IDENTITY CASCADE");
    }
  }

  @AfterEach
  void clear() {
    OrgContext.clear();
  }

  @Test
  void roundTripsConditionsAndActionsAsJsonb() throws Exception {
    JsonNode conditions = mapper.readTree("{\"level\":{\"in\":[\"fatal\",\"error\"]}}");
    JsonNode actions = mapper.readTree("{\"destinationIds\":[1,2]}");

    AlertRule created = repository.create(101L, "fatals", conditions, actions, 600, true);
    AlertRule loaded = repository.find(101L, created.id()).orElseThrow();

    assertThat(loaded.name()).isEqualTo("fatals");
    assertThat(loaded.throttleSeconds()).isEqualTo(600);
    assertThat(loaded.enabled()).isTrue();
    assertThat(loaded.conditions().path("level").path("in").get(0).asText()).isEqualTo("fatal");
    assertThat(loaded.actions().path("destinationIds").get(0).asInt()).isEqualTo(1);
  }

  @Test
  void listOrdersByCreatedDescId() throws Exception {
    JsonNode actions = mapper.readTree("{\"destinationIds\":[1]}");
    AlertRule first = repository.create(101L, "a", mapper.createObjectNode(), actions, 300, true);
    Thread.sleep(5);
    AlertRule second = repository.create(101L, "b", mapper.createObjectNode(), actions, 300, true);

    List<AlertRule> page = repository.listForProject(101L);
    assertThat(page).extracting(AlertRule::id).containsExactly(second.id(), first.id());
  }

  @Test
  void updateReplacesAllFieldsExceptIdAndProject() throws Exception {
    JsonNode actions = mapper.readTree("{\"destinationIds\":[1]}");
    AlertRule created =
        repository.create(101L, "old", mapper.createObjectNode(), actions, 300, true);

    Optional<AlertRule> updated =
        repository.update(
            101L,
            created.id(),
            "new",
            mapper.readTree("{\"occurrenceThreshold\":50}"),
            mapper.readTree("{\"destinationIds\":[2,3]}"),
            900,
            false);

    assertThat(updated).isPresent();
    AlertRule u = updated.orElseThrow();
    assertThat(u.name()).isEqualTo("new");
    assertThat(u.throttleSeconds()).isEqualTo(900);
    assertThat(u.enabled()).isFalse();
    assertThat(u.conditions().path("occurrenceThreshold").asInt()).isEqualTo(50);
    assertThat(u.actions().path("destinationIds")).hasSize(2);
  }

  @Test
  void deleteIsAccountedFor() throws Exception {
    AlertRule created =
        repository.create(
            101L,
            "x",
            mapper.createObjectNode(),
            mapper.readTree("{\"destinationIds\":[1]}"),
            300,
            true);
    assertThat(repository.delete(101L, created.id())).isTrue();
    assertThat(repository.delete(101L, created.id())).isFalse();
    assertThat(repository.find(101L, created.id())).isEmpty();
  }

  @Test
  void wrongProjectCannotSeeOrMutate() throws Exception {
    AlertRule created =
        repository.create(
            101L,
            "x",
            mapper.createObjectNode(),
            mapper.readTree("{\"destinationIds\":[1]}"),
            300,
            true);

    assertThat(repository.find(102L, created.id())).isEmpty();
    assertThat(repository.delete(102L, created.id())).isFalse();
  }

  private static void seed(DataSource ds) throws Exception {
    try (Connection conn = ds.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme')");
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (2, 'other', 'Other')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (101, 1, 'web', 'Web', 'javascript')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (102, 2, 'web', 'Web', 'javascript')");
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
