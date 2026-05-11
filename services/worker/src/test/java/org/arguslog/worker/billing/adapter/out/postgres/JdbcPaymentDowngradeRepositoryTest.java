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
import java.util.UUID;
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

/**
 * V27+ per-user payment downgrade. State lives on users.plan / users.payment_grace_until; this test
 * seeds users (with owned orgs so the repository can resolve org ids for the return value) and
 * asserts the user row flips to free + the org id is returned.
 */
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
    new org.springframework.jdbc.core.JdbcTemplate(dataSource)
        .execute("DELETE FROM users WHERE email LIKE 'downgrade-test-%'");
  }

  @Test
  void downgradesProUserWithExpiredGrace() throws Exception {
    UUID owner = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001");
    seedUser(owner, "downgrade-test-1@example.com", "pro", Instant.parse("2026-05-01T00:00:00Z"));
    long orgId = seedOrg("org-1", "Org 1");
    seedMembership(orgId, owner);

    List<Long> orgIds = repo.downgradeExpired(Instant.parse("2026-05-13T04:00:00Z"));

    assertThat(orgIds).containsExactly(orgId);
    assertUserPlan(owner, "free");
    assertUserGrace(owner, null);
  }

  @Test
  void leavesUserWithStillFutureGraceAlone() throws Exception {
    UUID owner = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0002");
    Instant future = Instant.parse("2026-05-20T00:00:00Z");
    seedUser(owner, "downgrade-test-2@example.com", "pro", future);
    long orgId = seedOrg("org-2", "Org 2");
    seedMembership(orgId, owner);

    List<Long> orgIds = repo.downgradeExpired(Instant.parse("2026-05-13T04:00:00Z"));

    assertThat(orgIds).isEmpty();
    assertUserPlan(owner, "pro");
  }

  @Test
  void leavesUserWithoutGraceAlone() throws Exception {
    UUID owner = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0003");
    seedUser(owner, "downgrade-test-3@example.com", "pro", null);
    long orgId = seedOrg("org-3", "Org 3");
    seedMembership(orgId, owner);

    List<Long> orgIds = repo.downgradeExpired(Instant.parse("2026-05-13T04:00:00Z"));

    assertThat(orgIds).isEmpty();
    assertUserPlan(owner, "pro");
  }

  @Test
  void leavesAlreadyFreeUserAloneEvenIfGraceWasOpenedWindowAgo() throws Exception {
    // Sequence: payment failed, grace opened, then subscription deleted webhook flipped plan
    // to FREE before the worker ran. Worker must not "downgrade" an already-Free row.
    UUID owner = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0004");
    seedUser(owner, "downgrade-test-4@example.com", "free", Instant.parse("2026-05-01T00:00:00Z"));
    long orgId = seedOrg("org-4", "Org 4");
    seedMembership(orgId, owner);

    List<Long> orgIds = repo.downgradeExpired(Instant.parse("2026-05-13T04:00:00Z"));

    assertThat(orgIds).isEmpty();
  }

  @Test
  void downgradesStarterAndBusinessTiersToo() throws Exception {
    // Original predicate was `plan = 'pro'` — V23 added STARTER + BUSINESS and the worker silently
    // ignored their expired grace. V27 broadens to `plan != 'free'` so every paid tier downgrades.
    UUID starterOwner = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0005");
    UUID businessOwner = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0006");
    seedUser(
        starterOwner,
        "downgrade-test-5@example.com",
        "starter",
        Instant.parse("2026-05-01T00:00:00Z"));
    seedUser(
        businessOwner,
        "downgrade-test-6@example.com",
        "business",
        Instant.parse("2026-05-02T00:00:00Z"));
    long starterOrg = seedOrg("org-5", "Starter Org");
    long businessOrg = seedOrg("org-6", "Business Org");
    seedMembership(starterOrg, starterOwner);
    seedMembership(businessOrg, businessOwner);

    List<Long> orgIds = repo.downgradeExpired(Instant.parse("2026-05-13T04:00:00Z"));

    assertThat(orgIds).containsExactlyInAnyOrder(starterOrg, businessOrg);
    assertUserPlan(starterOwner, "free");
    assertUserPlan(businessOwner, "free");
  }

  @Test
  void downgradesMultipleUsersAtomically() throws Exception {
    UUID o1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0010");
    UUID o2 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0011");
    UUID o3 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0012");
    UUID o4 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0013");
    seedUser(o1, "downgrade-test-10@example.com", "pro", Instant.parse("2026-04-30T00:00:00Z"));
    seedUser(o2, "downgrade-test-11@example.com", "pro", Instant.parse("2026-05-22T00:00:00Z"));
    seedUser(o3, "downgrade-test-12@example.com", "pro", Instant.parse("2026-05-01T00:00:00Z"));
    seedUser(o4, "downgrade-test-13@example.com", "free", Instant.parse("2026-04-01T00:00:00Z"));
    long org1 = seedOrg("org-10", "Org 10");
    long org2 = seedOrg("org-11", "Org 11");
    long org3 = seedOrg("org-12", "Org 12");
    long org4 = seedOrg("org-13", "Org 13");
    seedMembership(org1, o1);
    seedMembership(org2, o2);
    seedMembership(org3, o3);
    seedMembership(org4, o4);

    List<Long> orgIds = repo.downgradeExpired(Instant.parse("2026-05-13T04:00:00Z"));

    assertThat(orgIds).containsExactlyInAnyOrder(org1, org3);
    assertUserPlan(o2, "pro");
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static long seedOrg(String slug, String name) throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO organizations (slug, name) VALUES (?, ?) RETURNING id")) {
      stmt.setString(1, slug);
      stmt.setString(2, name);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static void seedUser(UUID id, String email, String plan, Instant graceUntil)
      throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO users (id, email, display_name, plan, payment_grace_until)"
                    + " VALUES (?, ?, ?, ?::org_plan, ?)")) {
      stmt.setObject(1, id);
      stmt.setString(2, email);
      stmt.setString(3, email);
      stmt.setString(4, plan);
      if (graceUntil == null) stmt.setNull(5, java.sql.Types.TIMESTAMP);
      else stmt.setTimestamp(5, Timestamp.from(graceUntil));
      stmt.execute();
    }
  }

  private static void seedMembership(long orgId, UUID userId) throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO org_members (org_id, user_id, role) VALUES (?, ?, 'owner'::org_role)")) {
      stmt.setLong(1, orgId);
      stmt.setObject(2, userId);
      stmt.execute();
    }
  }

  private static void assertUserPlan(UUID id, String expected) throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement("SELECT plan::text FROM users WHERE id = ?")) {
      stmt.setObject(1, id);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        assertThat(rs.getString(1)).isEqualTo(expected);
      }
    }
  }

  private static void assertUserGrace(UUID id, Instant expected) throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement("SELECT payment_grace_until FROM users WHERE id = ?")) {
      stmt.setObject(1, id);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        Timestamp ts = rs.getTimestamp(1);
        if (expected == null) {
          assertThat(ts).isNull();
        } else {
          assertThat(ts.toInstant()).isEqualTo(expected);
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
