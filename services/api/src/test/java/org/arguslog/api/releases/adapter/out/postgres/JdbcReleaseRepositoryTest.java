package org.arguslog.api.releases.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.domain.Release;
import org.arguslog.api.security.OrgContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class JdbcReleaseRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static ReleaseRepository repository;

  @BeforeAll
  static void boot() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    seed(dataSource);

    TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    JdbcReleaseRepository raw = new JdbcReleaseRepository(dataSource);
    repository =
        new ReleaseRepository() {
          @Override
          public Release create(long projectId, String version) {
            return tx.execute(s -> raw.create(projectId, version));
          }

          @Override
          public List<Release> listForProject(long projectId) {
            return tx.execute(s -> raw.listForProject(projectId));
          }

          @Override
          public Optional<Release> find(long projectId, long id) {
            return tx.execute(s -> raw.find(projectId, id));
          }

          @Override
          public Optional<Release> findByVersion(long projectId, String version) {
            return tx.execute(s -> raw.findByVersion(projectId, version));
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
      exec(conn, "TRUNCATE releases RESTART IDENTITY CASCADE");
    }
  }

  @AfterEach
  void clear() {
    OrgContext.clear();
  }

  @Test
  void createReturnsPersistedRowWithGeneratedFields() {
    Release out = repository.create(101L, "1.0.0");
    assertThat(out.id()).isPositive();
    assertThat(out.projectId()).isEqualTo(101L);
    assertThat(out.version()).isEqualTo("1.0.0");
    assertThat(out.createdAt()).isNotNull();
  }

  @Test
  void duplicateProjectVersionTriggersDuplicateKey() {
    repository.create(101L, "1.0.0");
    assertThatThrownBy(() -> repository.create(101L, "1.0.0"))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void sameVersionAcrossDifferentProjectsIsAllowed() {
    repository.create(101L, "1.0.0");
    OrgContext.set(2L); // org 2 owns project 102
    Release other = repository.create(102L, "1.0.0");
    assertThat(other.projectId()).isEqualTo(102L);
  }

  @Test
  void listOrdersByCreatedDescThenIdDesc() throws Exception {
    Release first = repository.create(101L, "1.0.0");
    Thread.sleep(5);
    Release second = repository.create(101L, "1.0.1");

    List<Release> page = repository.listForProject(101L);
    assertThat(page).extracting(Release::id).containsExactly(second.id(), first.id());
  }

  @Test
  void findByIdRespectsProjectScope() {
    Release inProject101 = repository.create(101L, "1.0.0");

    assertThat(repository.find(101L, inProject101.id())).isPresent();
    // Wrong project id (even within the same org) returns empty.
    assertThat(repository.find(102L, inProject101.id())).isEmpty();
  }

  @Test
  void findByVersionReturnsTheRow() {
    Release created = repository.create(101L, "v2.0.0-rc1");
    assertThat(repository.findByVersion(101L, "v2.0.0-rc1")).contains(created);
    assertThat(repository.findByVersion(101L, "missing")).isEmpty();
  }

  // RLS-isolation cannot be exercised here: Testcontainers logs in as the table owner, who
  // bypasses RLS by default. Prod uses a non-owner role so the policy fires there. Adding
  // FORCE ROW LEVEL SECURITY just for tests would diverge from the rest of the schema and
  // hide a real owner-bypass risk. Tracked: tests/integration owner-vs-app-role split (P4).

  // ── helpers ─────────────────────────────────────────────────────────────

  private static void seed(DataSource ds) throws Exception {
    try (Connection conn = ds.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme')");
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (2, 'other', 'Other')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (101, 1, 'web', 'Web', 'javascript')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (102, 2, 'api', 'Api', 'java')");
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
