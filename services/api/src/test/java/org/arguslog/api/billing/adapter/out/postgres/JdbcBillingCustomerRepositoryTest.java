package org.arguslog.api.billing.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.arguslog.billing.PlanTier;
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
 * Per-user billing (V27+): JdbcBillingCustomerRepository writes target the org's primary-owner
 * user row directly. Test seeds an org + owner user so the primary-owner subquery resolves, and
 * asserts each operation lands on users.* instead of the (now-dropped) organizations.* columns.
 */
@Testcontainers
class JdbcBillingCustomerRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static HikariDataSource dataSource;
  private static BillingCustomerRepository repo;

  @BeforeAll
  static void boot() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    repo = new JdbcBillingCustomerRepository(dataSource);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void seed() throws Exception {
    new org.springframework.jdbc.core.JdbcTemplate(dataSource)
        .execute("TRUNCATE organizations RESTART IDENTITY CASCADE");
    new org.springframework.jdbc.core.JdbcTemplate(dataSource)
        .execute("DELETE FROM users WHERE email LIKE 'billing-test-%'");
    try (Connection conn = dataSource.getConnection()) {
      try (PreparedStatement stmt =
          conn.prepareStatement(
              "INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)")) {
        stmt.setObject(1, OWNER);
        stmt.setString(2, "billing-test-owner@example.com");
        stmt.setString(3, "Billing Test Owner");
        stmt.execute();
      }
      try (PreparedStatement stmt =
          conn.prepareStatement("INSERT INTO organizations (id, slug, name) VALUES (?, ?, ?)")) {
        stmt.setLong(1, 1L);
        stmt.setString(2, "acme");
        stmt.setString(3, "Acme");
        stmt.execute();
      }
      try (PreparedStatement stmt =
          conn.prepareStatement(
              "INSERT INTO org_members (org_id, user_id, role) VALUES (?, ?, 'owner'::org_role)")) {
        stmt.setLong(1, 1L);
        stmt.setObject(2, OWNER);
        stmt.execute();
      }
    }
  }

  @Test
  void findCustomerIdReturnsEmptyForNeverChecked() {
    assertThat(repo.findCustomerId(1L)).isEmpty();
    assertThat(repo.findCustomerIdForUser(OWNER)).isEmpty();
  }

  @Test
  void saveAndFindCustomerIdRoundTrips() {
    repo.saveCustomerId(1L, "cus_test_1");
    assertThat(repo.findCustomerId(1L)).contains("cus_test_1");
    assertThat(repo.findCustomerIdForUser(OWNER)).contains("cus_test_1");
  }

  @Test
  void findOrgIdByCustomerIdResolvesReverse() {
    repo.saveCustomerId(1L, "cus_test_1");
    assertThat(repo.findOrgIdByCustomerId("cus_test_1")).contains(1L);
    assertThat(repo.findOrgIdByCustomerId("cus_unknown")).isEmpty();
    assertThat(repo.findUserIdByCustomerId("cus_test_1")).contains(OWNER);
    assertThat(repo.findUserIdByCustomerId("cus_unknown")).isEmpty();
  }

  @Test
  void updatePlanAndRenewalSetsBothColumnsOnOwnerUser() throws Exception {
    Instant renews = Instant.parse("2026-06-15T00:00:00Z");
    repo.updatePlanAndRenewal(1L, PlanTier.PRO.dbValue(), renews);

    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement("SELECT plan::text, plan_renews_at FROM users WHERE id = ?")) {
      stmt.setObject(1, OWNER);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        assertThat(rs.getString(1)).isEqualTo("pro");
        Timestamp ts = rs.getTimestamp(2);
        assertThat(ts.toInstant()).isEqualTo(renews);
      }
    }
  }

  @Test
  void updatePlanAndRenewalAcceptsNullRenewal() throws Exception {
    repo.updatePlanAndRenewal(1L, PlanTier.FREE.dbValue(), null);

    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement("SELECT plan::text, plan_renews_at FROM users WHERE id = ?")) {
      stmt.setObject(1, OWNER);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        assertThat(rs.getString(1)).isEqualTo("free");
        assertThat(rs.getTimestamp(2)).isNull();
      }
    }
  }

  @Test
  void openPaymentGraceWritesWhenColumnIsNull() throws Exception {
    Instant graceUntil = Instant.now().plusSeconds(7 * 24 * 3600);

    boolean written = repo.openPaymentGrace(1L, graceUntil);

    assertThat(written).isTrue();
    assertThat(readGrace(OWNER))
        .isCloseTo(graceUntil, within(1, java.time.temporal.ChronoUnit.SECONDS));
  }

  @Test
  void openPaymentGraceIsAnIdempotentNoOpWhileWindowIsActive() throws Exception {
    Instant first = Instant.now().plusSeconds(7 * 24 * 3600);
    boolean firstWrite = repo.openPaymentGrace(1L, first);
    Instant second = first.plusSeconds(7 * 24 * 3600);

    boolean secondWrite = repo.openPaymentGrace(1L, second);

    assertThat(firstWrite).isTrue();
    assertThat(secondWrite).isFalse();
    // Stored value must still be the first deadline — Smart Retries cannot extend the window.
    assertThat(readGrace(OWNER)).isCloseTo(first, within(1, java.time.temporal.ChronoUnit.SECONDS));
  }

  @Test
  void openPaymentGraceReopensAfterPreviousLapsed() throws Exception {
    // Seed an already-lapsed grace timestamp directly on the owner user.
    setGrace(OWNER, Instant.now().minusSeconds(60));
    Instant fresh = Instant.now().plusSeconds(7 * 24 * 3600);

    boolean written = repo.openPaymentGrace(1L, fresh);

    assertThat(written).isTrue();
    assertThat(readGrace(OWNER))
        .isCloseTo(fresh, within(1, java.time.temporal.ChronoUnit.SECONDS));
  }

  @Test
  void clearPaymentGraceResetsTheColumn() throws Exception {
    setGrace(OWNER, Instant.now().plusSeconds(3600));

    repo.clearPaymentGrace(1L);

    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement("SELECT payment_grace_until FROM users WHERE id = ?")) {
      stmt.setObject(1, OWNER);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        assertThat(rs.getTimestamp(1)).isNull();
      }
    }
  }

  private static Instant readGrace(UUID userId) throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement("SELECT payment_grace_until FROM users WHERE id = ?")) {
      stmt.setObject(1, userId);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        Timestamp ts = rs.getTimestamp(1);
        return ts == null ? null : ts.toInstant();
      }
    }
  }

  private static void setGrace(UUID userId, Instant value) throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement("UPDATE users SET payment_grace_until = ? WHERE id = ?")) {
      stmt.setTimestamp(1, Timestamp.from(value));
      stmt.setObject(2, userId);
      stmt.execute();
    }
  }
}
