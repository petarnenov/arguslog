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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.arguslog.worker.billing.application.port.RenewalReminderRepository;
import org.arguslog.worker.billing.application.port.RenewalReminderRepository.ReminderCandidate;
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
 * V27+ per-user renewal reminder lookup. Asserts the daily cron reads plan + plan_renews_at from
 * the owner-user row (not organizations — those columns are dropped) and dedup-writes by org_id so
 * a user owning N orgs gets N targeted emails. Regression guard for the bug where the SQL kept
 * referencing dropped {@code organizations.plan} / {@code organizations.plan_renews_at}.
 */
@Testcontainers
class JdbcRenewalReminderRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static RenewalReminderRepository repo;

  @BeforeAll
  static void boot() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Flyway.configure().dataSource(dataSource).locations(resolveMigrations()).load().migrate();
    repo = new JdbcRenewalReminderRepository(dataSource);
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
        .execute("DELETE FROM users WHERE email LIKE 'reminder-test-%'");
  }

  @Test
  void findsProUserExpiringOnTargetDate() throws Exception {
    UUID owner = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccc0001");
    LocalDate target = LocalDate.of(2026, 5, 27);
    seedUser(
        owner,
        "reminder-test-1@example.com",
        "pro",
        target.atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
    long orgId = seedOrg("acme", "Acme");
    seedMembership(orgId, owner);

    List<ReminderCandidate> candidates = repo.findCandidates(target, 14);

    assertThat(candidates).hasSize(1);
    ReminderCandidate c = candidates.get(0);
    assertThat(c.orgId()).isEqualTo(orgId);
    assertThat(c.orgSlug()).isEqualTo("acme");
    assertThat(c.orgName()).isEqualTo("Acme");
    assertThat(c.ownerEmail()).isEqualTo("reminder-test-1@example.com");
    assertThat(c.planExpiresAt()).isEqualTo(target);
  }

  @Test
  void findsStarterAndBusinessTiersToo() throws Exception {
    // Pre-V23 holdover was `plan = 'pro'` — starter/business owners would silently skip the
    // T-14/-7/-1 reminder cycle. V27+ broadens to `plan != 'free'`.
    UUID starterOwner = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccc0002");
    UUID businessOwner = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccc0003");
    LocalDate target = LocalDate.of(2026, 5, 27);
    Instant renews = target.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
    seedUser(starterOwner, "reminder-test-2@example.com", "starter", renews);
    seedUser(businessOwner, "reminder-test-3@example.com", "business", renews);
    long starterOrg = seedOrg("starter-co", "Starter Co");
    long businessOrg = seedOrg("biz-co", "Biz Co");
    seedMembership(starterOrg, starterOwner);
    seedMembership(businessOrg, businessOwner);

    List<ReminderCandidate> candidates = repo.findCandidates(target, 7);

    assertThat(candidates)
        .extracting(ReminderCandidate::orgId)
        .containsExactlyInAnyOrder(starterOrg, businessOrg);
  }

  @Test
  void skipsFreeUserEvenWithPlanRenewsAtSet() throws Exception {
    UUID owner = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccc0004");
    LocalDate target = LocalDate.of(2026, 5, 27);
    // Plausible state if a user downgraded but kept their old plan_renews_at metadata.
    seedUser(
        owner,
        "reminder-test-4@example.com",
        "free",
        target.atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
    long orgId = seedOrg("free-co", "Free Co");
    seedMembership(orgId, owner);

    assertThat(repo.findCandidates(target, 1)).isEmpty();
  }

  @Test
  void skipsOrgWithReminderAlreadySent() throws Exception {
    UUID owner = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccc0005");
    LocalDate target = LocalDate.of(2026, 5, 27);
    seedUser(
        owner,
        "reminder-test-5@example.com",
        "pro",
        target.atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
    long orgId = seedOrg("sent-co", "Sent Co");
    seedMembership(orgId, owner);

    // First call returns the candidate.
    assertThat(repo.findCandidates(target, 14)).hasSize(1);
    // Mark sent and verify the dedup row is in place.
    assertThat(repo.markSent(orgId, target, 14)).isTrue();
    // Second call returns no candidates for the same (target, kind).
    assertThat(repo.findCandidates(target, 14)).isEmpty();
    // But T-7 for the same org is independent.
    assertThat(repo.findCandidates(target, 7)).hasSize(1);
  }

  @Test
  void markSentIsIdempotent() throws Exception {
    UUID owner = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccc0006");
    LocalDate target = LocalDate.of(2026, 5, 27);
    seedUser(
        owner,
        "reminder-test-6@example.com",
        "pro",
        target.atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
    long orgId = seedOrg("idem-co", "Idem Co");
    seedMembership(orgId, owner);

    assertThat(repo.markSent(orgId, target, 14)).isTrue();
    // Sibling worker re-runs the same insert — must report it didn't win the race.
    assertThat(repo.markSent(orgId, target, 14)).isFalse();
  }

  @Test
  void singleUserOwningMultipleOrgsYieldsCandidatePerOrg() throws Exception {
    // Per-user billing: user owns N orgs, all share the plan. We send one reminder per org because
    // the email body is org-scoped and the dedup table is keyed by org_id.
    UUID owner = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccc0007");
    LocalDate target = LocalDate.of(2026, 5, 27);
    seedUser(
        owner,
        "reminder-test-7@example.com",
        "pro",
        target.atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
    long org1 = seedOrg("multi-1", "Multi 1");
    long org2 = seedOrg("multi-2", "Multi 2");
    seedMembership(org1, owner);
    seedMembership(org2, owner);

    List<ReminderCandidate> candidates = repo.findCandidates(target, 14);

    assertThat(candidates)
        .extracting(ReminderCandidate::orgId)
        .containsExactlyInAnyOrder(org1, org2);
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

  private static void seedUser(UUID id, String email, String plan, Instant renewsAt)
      throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO users (id, email, display_name, plan, plan_renews_at)"
                    + " VALUES (?, ?, ?, ?::org_plan, ?)")) {
      stmt.setObject(1, id);
      stmt.setString(2, email);
      stmt.setString(3, email);
      stmt.setString(4, plan);
      if (renewsAt == null) stmt.setNull(5, java.sql.Types.TIMESTAMP);
      else stmt.setTimestamp(5, Timestamp.from(renewsAt));
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
