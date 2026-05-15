package org.arguslog.api.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.domain.Member;
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
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
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
    if (dataSource != null) dataSource.close();
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

  @Test
  void listMembersFlagsUnseenInviteeAsPending() throws Exception {
    // Org 2 carries two members: PAULA — placeholder from an invite, never logged in
    // (last_seen_at IS NULL) → pending; QUINN — signed-in member (last_seen_at = NOW()) → not
    // pending. Same SQL path covers both rows in one query so the boolean projection is exercised
    // both ways.
    UUID paula = UUID.fromString("00000000-0000-0000-0000-000000000010");
    UUID quinn = UUID.fromString("00000000-0000-0000-0000-000000000011");
    try (Connection conn = dataSource.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (2, 'pending-org', 'PendOrg')");
      try (PreparedStatement stmt =
          conn.prepareStatement(
              "INSERT INTO users (id, email, display_name, last_seen_at) VALUES (?, ?, ?, ?)")) {
        stmt.setObject(1, paula);
        stmt.setString(2, "paula@example.com");
        stmt.setString(3, null);
        stmt.setTimestamp(4, null);
        stmt.execute();
        stmt.setObject(1, quinn);
        stmt.setString(2, "quinn@example.com");
        stmt.setString(3, "Quinn");
        stmt.setTimestamp(4, new java.sql.Timestamp(System.currentTimeMillis()));
        stmt.execute();
      }
      try (PreparedStatement stmt =
          conn.prepareStatement(
              "INSERT INTO org_members (org_id, user_id, role, added_at) VALUES (?, ?,"
                  + " 'member'::org_role, ?)")) {
        // added_at controls ORDER BY — give Paula earlier ts so the assertion order is stable.
        stmt.setLong(1, 2L);
        stmt.setObject(2, paula);
        stmt.setTimestamp(3, java.sql.Timestamp.valueOf("2026-05-13 10:00:00"));
        stmt.execute();
        stmt.setLong(1, 2L);
        stmt.setObject(2, quinn);
        stmt.setTimestamp(3, java.sql.Timestamp.valueOf("2026-05-13 11:00:00"));
        stmt.execute();
      }
    }

    List<Member> members = repository.listMembersOf(2L);

    assertThat(members)
        .extracting(Member::userId, Member::displayName, Member::pending)
        .containsExactly(tuple(paula, null, true), tuple(quinn, "Quinn", false));
  }

  private static void seed(DataSource ds) throws Exception {
    try (Connection conn = ds.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme')");
      try (PreparedStatement stmt =
          conn.prepareStatement("INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)")) {
        stmt.setObject(1, ALICE);
        stmt.setString(2, "alice@example.com");
        stmt.setString(3, "Alice");
        stmt.execute();
        stmt.setObject(1, BOB);
        stmt.setString(2, "bob@example.com");
        stmt.setString(3, "Bob");
        stmt.execute();
      }
      try (PreparedStatement stmt =
          conn.prepareStatement(
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
