package org.arguslog.worker.retention.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.arguslog.worker.retention.application.port.RetentionPurgeRepository;
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
class JdbcRetentionPurgeRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static RetentionPurgeRepository repo;

  @BeforeAll
  static void boot() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Flyway.configure().dataSource(dataSource).locations(resolveMigrations()).load().migrate();
    repo = new JdbcRetentionPurgeRepository(dataSource);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void seed() throws Exception {
    var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
    jdbc.execute("TRUNCATE events");
    jdbc.execute("TRUNCATE issues RESTART IDENTITY CASCADE");
    jdbc.execute("TRUNCATE projects RESTART IDENTITY CASCADE");
    jdbc.execute("TRUNCATE organizations RESTART IDENTITY CASCADE");
    insertOrg(1L, "acme");
    insertOrg(2L, "other");
    insertProject(101L, 1L, "web");
    insertProject(102L, 1L, "api");
    insertProject(201L, 2L, "web");
    insertIssue(701L, 101L, "fp-a");
    insertIssue(702L, 102L, "fp-b");
    insertIssue(801L, 201L, "fp-other");
  }

  @Test
  void countEligibleReturnsRowsForOrgOlderThanCutoff() throws Exception {
    insertEvent(101L, 701L, Instant.parse("2026-01-01T00:00:00Z"));
    insertEvent(101L, 701L, Instant.parse("2026-04-01T00:00:00Z"));
    insertEvent(101L, 701L, Instant.parse("2026-05-05T00:00:00Z"));
    insertEvent(201L, 801L, Instant.parse("2026-01-01T00:00:00Z")); // other org

    long n = repo.countEligible(1L, Instant.parse("2026-04-15T00:00:00Z"));

    assertThat(n).isEqualTo(2L);
  }

  @Test
  void purgeBatchDeletesOnlyMatchingOrgsRowsBelowCutoff() throws Exception {
    insertEvent(101L, 701L, Instant.parse("2026-01-01T00:00:00Z"));
    insertEvent(102L, 702L, Instant.parse("2026-02-01T00:00:00Z"));
    insertEvent(101L, 701L, Instant.parse("2026-05-05T00:00:00Z")); // newer than cutoff
    insertEvent(201L, 801L, Instant.parse("2026-01-01T00:00:00Z")); // other org — must survive

    int deleted = repo.purgeBatch(1L, Instant.parse("2026-04-15T00:00:00Z"), 100);

    assertThat(deleted).isEqualTo(2);
    assertThat(countEvents()).isEqualTo(2L); // 1 newer in org 1 + 1 in org 2
  }

  @Test
  void purgeBatchRespectsLimitForLargerBacklogs() throws Exception {
    Instant base = Instant.parse("2026-01-01T00:00:00Z");
    for (int i = 0; i < 25; i++) {
      insertEvent(101L, 701L, base.plusSeconds(i));
    }

    int firstBatch = repo.purgeBatch(1L, Instant.parse("2026-04-15T00:00:00Z"), 10);
    int secondBatch = repo.purgeBatch(1L, Instant.parse("2026-04-15T00:00:00Z"), 10);
    int thirdBatch = repo.purgeBatch(1L, Instant.parse("2026-04-15T00:00:00Z"), 10);
    int fourthBatch = repo.purgeBatch(1L, Instant.parse("2026-04-15T00:00:00Z"), 10);

    assertThat(firstBatch).isEqualTo(10);
    assertThat(secondBatch).isEqualTo(10);
    assertThat(thirdBatch).isEqualTo(5);
    assertThat(fourthBatch).isZero();
    assertThat(countEvents()).isZero();
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static void insertOrg(long id, String slug) throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement("INSERT INTO organizations (id, slug, name) VALUES (?, ?, ?)")) {
      stmt.setLong(1, id);
      stmt.setString(2, slug);
      stmt.setString(3, slug);
      stmt.execute();
    }
  }

  private static void insertProject(long id, long orgId, String slug) throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO projects (id, org_id, slug, name, platform)"
                    + " VALUES (?, ?, ?, ?, 'javascript')")) {
      stmt.setLong(1, id);
      stmt.setLong(2, orgId);
      stmt.setString(3, slug);
      stmt.setString(4, slug);
      stmt.execute();
    }
  }

  private static void insertIssue(long id, long projectId, String fingerprint) throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO issues (id, project_id, fingerprint, title, level,"
                    + " first_seen_at, last_seen_at, occurrence_count)"
                    + " VALUES (?, ?, ?, ?, 'error', NOW(), NOW(), 1)")) {
      stmt.setLong(1, id);
      stmt.setLong(2, projectId);
      stmt.setString(3, fingerprint);
      stmt.setString(4, "title-" + fingerprint);
      stmt.execute();
    }
  }

  private static void insertEvent(long projectId, long issueId, Instant receivedAt)
      throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO events (id, issue_id, project_id, received_at, payload)"
                    + " VALUES (?, ?, ?, ?, ?::jsonb)")) {
      stmt.setObject(1, UUID.randomUUID());
      stmt.setLong(2, issueId);
      stmt.setLong(3, projectId);
      stmt.setObject(4, Timestamp.from(receivedAt), Types.TIMESTAMP);
      stmt.setString(5, "{}");
      stmt.execute();
    }
  }

  private static long countEvents() throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM events");
        ResultSet rs = stmt.executeQuery()) {
      rs.next();
      return rs.getLong(1);
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
