package org.arguslog.api.admin.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.arguslog.api.admin.domain.AdminOrgRow;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Regression: production threw {@code PSQLException: No value specified for parameter 5} on {@code
 * GET /api/v1/admin/orgs} because {@link JdbcAdminQueryRepository#listOrgs} bound four params for a
 * five-{@code ?} query (the leading {@code ?::text IS NULL} guard was missed). The test fires every
 * code path the controller exercises — listing with no search, searching with a filter, paging — so
 * a future copy-paste regression on the bind list fails locally.
 */
@Testcontainers
class JdbcAdminQueryRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static JdbcAdminQueryRepository repo;

  @BeforeAll
  static void boot() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    repo = new JdbcAdminQueryRepository(dataSource);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void clean() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("TRUNCATE organizations RESTART IDENTITY CASCADE");
  }

  @Test
  void listOrgsReturnsAllRowsWhenSearchIsBlank() {
    seed("acme", "Acme Inc");
    seed("globex", "Globex");

    List<AdminOrgRow> rows = repo.listOrgs(null, 0, 20);

    assertThat(rows).extracting(AdminOrgRow::slug).containsExactlyInAnyOrder("acme", "globex");
    assertThat(repo.countOrgs(null)).isEqualTo(2);
  }

  @Test
  void listOrgsFiltersBySlugOrNameSubstring() {
    seed("acme", "Acme Inc");
    seed("globex", "Globex");
    seed("initech", "Initech");

    assertThat(repo.listOrgs("ini", 0, 20))
        .extracting(AdminOrgRow::slug)
        .containsExactly("initech");
    assertThat(repo.countOrgs("ini")).isEqualTo(1);
  }

  @Test
  void listOrgsAppliesLimitAndOffsetForPaging() {
    seed("a", "A");
    seed("b", "B");
    seed("c", "C");

    List<AdminOrgRow> first = repo.listOrgs(null, 0, 2);
    List<AdminOrgRow> second = repo.listOrgs(null, 2, 2);

    assertThat(first).hasSize(2);
    assertThat(second).hasSize(1);
    assertThat(repo.countOrgs(null)).isEqualTo(3);
  }

  private void seed(String slug, String name) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update(
        "INSERT INTO organizations (slug, name, plan) VALUES (?, ?, 'free'::org_plan)", slug, name);
  }
}
