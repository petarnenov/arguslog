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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.arguslog.worker.billing.application.port.PlanExpiryRepository;
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
 * V27+ per-user plan-expiry grace. Asserts the hourly cron writes {@code payment_grace_until} on
 * the user row (not organizations — that column was dropped) and returns affected owner-org ids.
 * Regression guard for the bug where {@link JdbcPlanExpiryRepository} kept querying the dropped
 * {@code organizations.plan} column post-V27, failing every hour and never opening any grace.
 */
@Testcontainers
class JdbcPlanExpiryRepositoryTest {

  private static final long GRACE_SECONDS = Duration.ofDays(7).toSeconds();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static PlanExpiryRepository repo;

  @BeforeAll
  static void boot() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Flyway.configure().dataSource(dataSource).locations(resolveMigrations()).load().migrate();
    repo = new JdbcPlanExpiryRepository(dataSource);
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
        .execute("DELETE FROM users WHERE email LIKE 'expiry-test-%'");
  }

  @Test
  void opensGraceForProUserWithLapsedRenewal() throws Exception {
    UUID owner = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0001");
    seedUser(
        owner, "expiry-test-1@example.com", "pro", Instant.parse("2026-05-01T00:00:00Z"), null);
    long orgId = seedOrg("org-1", "Org 1");
    seedMembership(orgId, owner);

    Instant now = Instant.parse("2026-05-13T10:00:00Z");
    List<Long> orgIds = repo.openGraceForExpiredPlans(now, GRACE_SECONDS);

    assertThat(orgIds).containsExactly(orgId);
    assertUserGrace(owner, now.plusSeconds(GRACE_SECONDS));
  }

  @Test
  void opensGraceForStarterAndBusinessTiersToo() throws Exception {
    // Pre-V23 holdover was `plan = 'pro'` — starter/business users would silently never get
    // grace opened. V27+ broadens to `plan != 'free'` so every paid tier triggers.
    UUID starterOwner = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0002");
    UUID businessOwner = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0003");
    seedUser(
        starterOwner,
        "expiry-test-2@example.com",
        "starter",
        Instant.parse("2026-05-01T00:00:00Z"),
        null);
    seedUser(
        businessOwner,
        "expiry-test-3@example.com",
        "business",
        Instant.parse("2026-05-02T00:00:00Z"),
        null);
    long starterOrg = seedOrg("org-2", "Starter Org");
    long businessOrg = seedOrg("org-3", "Business Org");
    seedMembership(starterOrg, starterOwner);
    seedMembership(businessOrg, businessOwner);

    Instant now = Instant.parse("2026-05-13T10:00:00Z");
    List<Long> orgIds = repo.openGraceForExpiredPlans(now, GRACE_SECONDS);

    assertThat(orgIds).containsExactlyInAnyOrder(starterOrg, businessOrg);
    assertUserGrace(starterOwner, now.plusSeconds(GRACE_SECONDS));
    assertUserGrace(businessOwner, now.plusSeconds(GRACE_SECONDS));
  }

  @Test
  void leavesFreeUserAlone() throws Exception {
    UUID owner = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0004");
    seedUser(
        owner, "expiry-test-4@example.com", "free", Instant.parse("2026-05-01T00:00:00Z"), null);
    long orgId = seedOrg("org-4", "Org 4");
    seedMembership(orgId, owner);

    Instant now = Instant.parse("2026-05-13T10:00:00Z");
    List<Long> orgIds = repo.openGraceForExpiredPlans(now, GRACE_SECONDS);

    assertThat(orgIds).isEmpty();
    assertUserGrace(owner, null);
  }

  @Test
  void leavesUserWithFutureRenewalAlone() throws Exception {
    UUID owner = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0005");
    seedUser(
        owner, "expiry-test-5@example.com", "pro", Instant.parse("2026-05-20T00:00:00Z"), null);
    long orgId = seedOrg("org-5", "Org 5");
    seedMembership(orgId, owner);

    Instant now = Instant.parse("2026-05-13T10:00:00Z");
    List<Long> orgIds = repo.openGraceForExpiredPlans(now, GRACE_SECONDS);

    assertThat(orgIds).isEmpty();
    assertUserGrace(owner, null);
  }

  @Test
  void isIdempotent_doesNotReopenAlreadyOpenGrace() throws Exception {
    // Grace already open — we must not overwrite, otherwise a re-run would slide the deadline.
    UUID owner = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0006");
    Instant existingGrace = Instant.parse("2026-05-18T00:00:00Z");
    seedUser(
        owner,
        "expiry-test-6@example.com",
        "pro",
        Instant.parse("2026-05-01T00:00:00Z"),
        existingGrace);
    long orgId = seedOrg("org-6", "Org 6");
    seedMembership(orgId, owner);

    Instant now = Instant.parse("2026-05-13T10:00:00Z");
    List<Long> orgIds = repo.openGraceForExpiredPlans(now, GRACE_SECONDS);

    assertThat(orgIds).isEmpty();
    assertUserGrace(owner, existingGrace);
  }

  @Test
  void opensGraceForMultipleUsersAtomically() throws Exception {
    UUID o1 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0010");
    UUID o2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0011");
    UUID o3 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0012");
    seedUser(o1, "expiry-test-10@example.com", "pro", Instant.parse("2026-04-30T00:00:00Z"), null);
    seedUser(o2, "expiry-test-11@example.com", "pro", Instant.parse("2026-05-22T00:00:00Z"), null);
    seedUser(o3, "expiry-test-12@example.com", "pro", Instant.parse("2026-05-01T00:00:00Z"), null);
    long org1 = seedOrg("org-10", "Org 10");
    long org2 = seedOrg("org-11", "Org 11");
    long org3 = seedOrg("org-12", "Org 12");
    seedMembership(org1, o1);
    seedMembership(org2, o2);
    seedMembership(org3, o3);

    Instant now = Instant.parse("2026-05-13T10:00:00Z");
    List<Long> orgIds = repo.openGraceForExpiredPlans(now, GRACE_SECONDS);

    assertThat(orgIds).containsExactlyInAnyOrder(org1, org3);
    assertUserGrace(o2, null);
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

  private static void seedUser(
      UUID id, String email, String plan, Instant renewsAt, Instant graceUntil) throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO users (id, email, display_name, plan, plan_renews_at,"
                    + " payment_grace_until) VALUES (?, ?, ?, ?::org_plan, ?, ?)")) {
      stmt.setObject(1, id);
      stmt.setString(2, email);
      stmt.setString(3, email);
      stmt.setString(4, plan);
      if (renewsAt == null) stmt.setNull(5, java.sql.Types.TIMESTAMP);
      else stmt.setTimestamp(5, Timestamp.from(renewsAt));
      if (graceUntil == null) stmt.setNull(6, java.sql.Types.TIMESTAMP);
      else stmt.setTimestamp(6, Timestamp.from(graceUntil));
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
