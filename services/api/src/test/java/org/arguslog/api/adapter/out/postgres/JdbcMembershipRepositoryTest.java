package org.arguslog.api.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.application.port.MembershipRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class JdbcMembershipRepositoryTest {

  private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
      DockerImageName.parse("timescale/timescaledb:latest-pg16")
          .asCompatibleSubstituteFor("postgres"))
      .withDatabaseName("arguslog")
      .withUsername("arguslog")
      .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static MembershipRepository repository;

  @BeforeAll
  static void boot() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    seed(dataSource);
    repository = new JdbcMembershipRepository(dataSource);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null)
      dataSource.close();
  }

  @Test
  void recognizesActualMember() {
    assertThat(repository.userIsMemberOfOrg(ALICE, 1L)).isTrue();
  }

  @Test
  void rejectsNonMember() {
    assertThat(repository.userIsMemberOfOrg(BOB, 1L)).isFalse();
  }

  @Test
  void rejectsMemberOfDifferentOrg() {
    assertThat(repository.userIsMemberOfOrg(ALICE, 999L)).isFalse();
  }

  private static void seed(DataSource ds) throws Exception {
    try (Connection conn = ds.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme')");
      try (PreparedStatement stmt = conn
          .prepareStatement("INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)")) {
        stmt.setObject(1, ALICE);
        stmt.setString(2, "alice@example.com");
        stmt.setString(3, "Alice");
        stmt.execute();
        stmt.setObject(1, BOB);
        stmt.setString(2, "bob@example.com");
        stmt.setString(3, "Bob");
        stmt.execute();
      }
      try (PreparedStatement stmt = conn.prepareStatement(
          "INSERT INTO org_members (org_id, user_id, role) VALUES (?, ?, 'member'::org_role)")) {
        stmt.setLong(1, 1L);
        stmt.setObject(2, ALICE);
        stmt.execute();
      }
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
