package org.arguslog.api.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.application.CursorCodec.LongCursor;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.domain.Issue;
import org.arguslog.api.security.OrgContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class JdbcIssueRepositoryTest {

  private static final DockerImageName TIMESCALE_IMAGE =
      DockerImageName.parse("timescale/timescaledb:latest-pg16")
          .asCompatibleSubstituteFor("postgres");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TIMESCALE_IMAGE)
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static IssueRepository repository;

  @BeforeAll
  static void boot() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);

    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    seedProject(dataSource);

    // The repo's pinOrgContextForRls calls SET LOCAL, which only sticks inside a
    // TX. Wrap every
    // call in a TransactionTemplate so the test exercises the production path.
    // Without this,
    // set_config(local=true) silently degrades to session scope and leaks across
    // pooled
    // connections — flaky tests in disguise.
    TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    JdbcIssueRepository raw = new JdbcIssueRepository(dataSource);
    repository =
        new IssueRepository() {
          @Override
          public List<Issue> page(
              long projectId,
              Optional<Issue.Status> status,
              Optional<Issue.Level> level,
              Optional<LongCursor> cursor,
              int limit) {
            return tx.execute(s -> raw.page(projectId, status, level, cursor, limit));
          }

          @Override
          public Optional<Issue> findByProjectAndId(long projectId, long issueId) {
            return tx.execute(s -> raw.findByProjectAndId(projectId, issueId));
          }
        };
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void primeOrgContext() throws Exception {
    OrgContext.set(1L); // tests query under the 'acme' org by default
    try (Connection conn = dataSource.getConnection()) {
      exec(conn, "TRUNCATE issues RESTART IDENTITY CASCADE");
    }
  }

  @AfterEach
  void clearOrgContext() {
    OrgContext.clear();
  }

  @Test
  void returnsIssuesOrderedByLastSeenDescIdDesc() {
    insertIssue("a", "error", "unresolved", Instant.parse("2026-05-05T10:00:00Z"));
    insertIssue("b", "error", "unresolved", Instant.parse("2026-05-05T12:00:00Z"));
    insertIssue("c", "error", "unresolved", Instant.parse("2026-05-05T11:00:00Z"));

    List<Issue> page =
        repository.page(101L, Optional.empty(), Optional.empty(), Optional.empty(), 10);
    assertThat(page).extracting(Issue::fingerprint).containsExactly("b", "c", "a");
  }

  @Test
  void scopesToProjectId() {
    insertIssue("acme", "error", "unresolved", Instant.now(), 101L);
    insertIssue("other", "error", "unresolved", Instant.now(), 102L);

    List<Issue> page =
        repository.page(101L, Optional.empty(), Optional.empty(), Optional.empty(), 10);
    assertThat(page).extracting(Issue::fingerprint).containsExactly("acme");
  }

  @Test
  void filtersByStatus() {
    insertIssue("u", "error", "unresolved", Instant.now());
    insertIssue("r", "error", "resolved", Instant.now().minusSeconds(60));
    insertIssue("i", "error", "ignored", Instant.now().minusSeconds(120));

    List<Issue> resolved =
        repository.page(
            101L, Optional.of(Issue.Status.RESOLVED), Optional.empty(), Optional.empty(), 10);
    assertThat(resolved).extracting(Issue::fingerprint).containsExactly("r");
  }

  @Test
  void filtersByLevel() {
    insertIssue("err", "error", "unresolved", Instant.now());
    insertIssue("warn", "warning", "unresolved", Instant.now().minusSeconds(60));

    List<Issue> warnings =
        repository.page(
            101L, Optional.empty(), Optional.of(Issue.Level.WARNING), Optional.empty(), 10);
    assertThat(warnings).extracting(Issue::fingerprint).containsExactly("warn");
  }

  @Test
  void cursorSeeksStrictlyPastTheGivenTuple() {
    Instant t = Instant.parse("2026-05-05T12:00:00Z");
    insertIssue("a", "error", "unresolved", t);
    insertIssue("b", "error", "unresolved", t); // same timestamp; tiebreak by id desc
    insertIssue("c", "error", "unresolved", t);

    List<Issue> first =
        repository.page(101L, Optional.empty(), Optional.empty(), Optional.empty(), 2);
    assertThat(first).hasSize(2);
    Issue last = first.get(1);

    List<Issue> next =
        repository.page(
            101L,
            Optional.empty(),
            Optional.empty(),
            Optional.of(new LongCursor(last.lastSeenAt(), last.id())),
            10);
    assertThat(next).hasSize(1);
    assertThat(next.get(0).id()).isLessThan(last.id());
  }

  @Test
  void respectsLimit() {
    for (int i = 0; i < 5; i++) {
      insertIssue("fp-" + i, "error", "unresolved", Instant.now().plusSeconds(i));
    }
    List<Issue> page =
        repository.page(101L, Optional.empty(), Optional.empty(), Optional.empty(), 3);
    assertThat(page).hasSize(3);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private void insertIssue(String fingerprint, String level, String status, Instant lastSeen) {
    insertIssue(fingerprint, level, status, lastSeen, 101L);
  }

  private void insertIssue(
      String fingerprint, String level, String status, Instant lastSeen, long projectId) {
    String sql =
        """
        INSERT INTO issues (project_id, environment_id, fingerprint, status, level, title,
                            culprit, first_seen_at, last_seen_at, occurrence_count)
             VALUES (?, NULL, ?, ?::issue_status, ?::event_level, ?, NULL, ?, ?, 1)
        """;
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setLong(1, projectId);
      stmt.setString(2, fingerprint);
      stmt.setString(3, status);
      stmt.setString(4, level);
      stmt.setString(5, "Title for " + fingerprint);
      stmt.setTimestamp(6, java.sql.Timestamp.from(lastSeen));
      stmt.setTimestamp(7, java.sql.Timestamp.from(lastSeen));
      stmt.execute();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void seedProject(DataSource ds) throws Exception {
    try (Connection conn = ds.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme')");
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (2, 'other', 'Other')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (101, 1, 'web', 'Web', 'javascript')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (102, 2, 'web', 'Web', 'javascript')");
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
