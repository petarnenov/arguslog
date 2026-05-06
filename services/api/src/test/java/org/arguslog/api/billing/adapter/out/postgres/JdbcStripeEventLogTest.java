package org.arguslog.api.billing.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.arguslog.api.billing.application.port.StripeEventLog;
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
class JdbcStripeEventLogTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static StripeEventLog log;

  @BeforeAll
  static void boot() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    log = new JdbcStripeEventLog(dataSource);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void clean() {
    new org.springframework.jdbc.core.JdbcTemplate(dataSource).execute("TRUNCATE stripe_events");
  }

  @Test
  void firstSightReturnsTrue() {
    assertThat(log.recordIfNew("evt_a", "checkout.session.completed")).isTrue();
  }

  @Test
  void duplicateEventIdReturnsFalse() {
    assertThat(log.recordIfNew("evt_a", "checkout.session.completed")).isTrue();
    assertThat(log.recordIfNew("evt_a", "checkout.session.completed")).isFalse();
    // The second call must not throw on the constraint violation.
    assertThat(log.recordIfNew("evt_a", "different.type.same.id")).isFalse();
  }

  @Test
  void differentEventIdsAreIndependent() {
    assertThat(log.recordIfNew("evt_a", "checkout.session.completed")).isTrue();
    assertThat(log.recordIfNew("evt_b", "customer.subscription.updated")).isTrue();
  }
}
