package org.arguslog.api.auth.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.auth.application.port.PatRepository;
import org.arguslog.api.auth.application.port.PatRepository.PatRow;
import org.arguslog.api.auth.domain.PersonalAccessToken;
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
class JdbcPatRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

  private static HikariDataSource dataSource;
  private static PatRepository repository;

  @BeforeAll
  static void boot() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    seed(dataSource);
    repository = new JdbcPatRepository(dataSource);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void clean() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      exec(conn, "TRUNCATE personal_access_tokens RESTART IDENTITY CASCADE");
    }
  }

  @Test
  void createReturnsPersistedRow() {
    PersonalAccessToken out =
        repository.create(USER, "ci-bot", "PREFIX01", "$argon2id$v=19$...", null);
    assertThat(out.id()).isPositive();
    assertThat(out.userId()).isEqualTo(USER);
    assertThat(out.name()).isEqualTo("ci-bot");
    assertThat(out.prefix()).isEqualTo("PREFIX01");
    assertThat(out.expiresAt()).isNull();
    assertThat(out.lastUsedAt()).isNull();
  }

  @Test
  void duplicatePrefixIsRejected() {
    repository.create(USER, "a", "DUPLICATE01".substring(0, 8), "$h$", null);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.create(USER, "b", "DUPLICATE01".substring(0, 8), "$h$", null))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
  }

  @Test
  void findByPrefixReturnsTokenAndStoredHash() {
    repository.create(USER, "ci", "FINDABLE", "$h$found", null);
    Optional<PatRow> found = repository.findByPrefix("FINDABLE");
    assertThat(found).isPresent();
    assertThat(found.orElseThrow().tokenHash()).isEqualTo("$h$found");
  }

  @Test
  void recordUsageBumpsLastUsedAt() {
    PersonalAccessToken created = repository.create(USER, "ci", "USAGE001", "$h$", null);
    Instant when = Instant.parse("2026-05-05T12:00:00Z");
    repository.recordUsage(created.id(), when);

    PersonalAccessToken refetched = repository.listForUser(USER).get(0);
    assertThat(refetched.lastUsedAt()).isEqualTo(when);
  }

  @Test
  void deleteScopedToOwner() {
    PersonalAccessToken created = repository.create(USER, "ci", "DEL00001", "$h$", null);
    assertThat(repository.deleteForUser(OTHER, created.id())).isFalse();
    assertThat(repository.deleteForUser(USER, created.id())).isTrue();
    assertThat(repository.findByPrefix("DEL00001")).isEmpty();
  }

  @Test
  void listForUserReturnsOnlyOwnTokensNewestFirst() throws Exception {
    repository.create(USER, "old", "OLDPREF1", "$h$", null);
    Thread.sleep(5);
    repository.create(USER, "new", "NEWPREF1", "$h$", null);
    repository.create(OTHER, "other", "OTHEPREF", "$h$", null);

    List<PersonalAccessToken> mine = repository.listForUser(USER);
    assertThat(mine).extracting(PersonalAccessToken::name).containsExactly("new", "old");
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static void seed(DataSource ds) throws Exception {
    try (Connection conn = ds.getConnection()) {
      try (PreparedStatement s =
          conn.prepareStatement("INSERT INTO users (id, email) VALUES (?, ?)")) {
        s.setObject(1, USER);
        s.setString(2, "user@example.com");
        s.execute();
        s.setObject(1, OTHER);
        s.setString(2, "other@example.com");
        s.execute();
      }
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
