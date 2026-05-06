package org.arguslog.api.billing.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.arguslog.api.billing.domain.PlanTier;
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
class JdbcBillingCustomerRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

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
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement("INSERT INTO organizations (id, slug, name) VALUES (?, ?, ?)")) {
      stmt.setLong(1, 1L);
      stmt.setString(2, "acme");
      stmt.setString(3, "Acme");
      stmt.execute();
    }
  }

  @Test
  void findCustomerIdReturnsEmptyForNeverChecked() {
    assertThat(repo.findCustomerId(1L)).isEmpty();
  }

  @Test
  void saveAndFindCustomerIdRoundTrips() {
    repo.saveCustomerId(1L, "cus_test_1");
    assertThat(repo.findCustomerId(1L)).contains("cus_test_1");
  }

  @Test
  void findOrgIdByCustomerIdResolvesReverse() {
    repo.saveCustomerId(1L, "cus_test_1");
    assertThat(repo.findOrgIdByCustomerId("cus_test_1")).contains(1L);
    assertThat(repo.findOrgIdByCustomerId("cus_unknown")).isEmpty();
  }

  @Test
  void updatePlanAndRenewalSetsBothColumns() throws Exception {
    Instant renews = Instant.parse("2026-06-15T00:00:00Z");
    repo.updatePlanAndRenewal(1L, PlanTier.PRO.dbValue(), renews);

    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "SELECT plan::text, plan_renews_at FROM organizations WHERE id = 1");
        ResultSet rs = stmt.executeQuery()) {
      rs.next();
      assertThat(rs.getString(1)).isEqualTo("pro");
      Timestamp ts = rs.getTimestamp(2);
      assertThat(ts.toInstant()).isEqualTo(renews);
    }
  }

  @Test
  void updatePlanAndRenewalAcceptsNullRenewal() throws Exception {
    repo.updatePlanAndRenewal(1L, PlanTier.FREE.dbValue(), null);

    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "SELECT plan::text, plan_renews_at FROM organizations WHERE id = 1");
        ResultSet rs = stmt.executeQuery()) {
      rs.next();
      assertThat(rs.getString(1)).isEqualTo("free");
      assertThat(rs.getTimestamp(2)).isNull();
    }
  }
}
