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
              Optional<String> searchText,
              Optional<org.arguslog.api.application.ListIssuesUseCase.AssigneeFilter> assignee,
              Optional<LongCursor> cursor,
              int limit) {
            return tx.execute(
                s -> raw.page(projectId, status, level, searchText, assignee, cursor, limit));
          }

          @Override
          public Optional<Issue> findByProjectAndId(long projectId, long issueId) {
            return tx.execute(s -> raw.findByProjectAndId(projectId, issueId));
          }

          @Override
          public Optional<Issue> updateStatus(long projectId, long issueId, Issue.Status status) {
            return tx.execute(s -> raw.updateStatus(projectId, issueId, status));
          }

          @Override
          public Optional<Issue> updateAssignee(
              long projectId, long issueId, java.util.UUID assigneeUserId) {
            return tx.execute(s -> raw.updateAssignee(projectId, issueId, assigneeUserId));
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
        repository.page(101L, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 10);
    assertThat(page).extracting(Issue::fingerprint).containsExactly("b", "c", "a");
  }

  @Test
  void scopesToProjectId() {
    insertIssue("acme", "error", "unresolved", Instant.now(), 101L);
    insertIssue("other", "error", "unresolved", Instant.now(), 102L);

    List<Issue> page =
        repository.page(101L, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 10);
    assertThat(page).extracting(Issue::fingerprint).containsExactly("acme");
  }

  @Test
  void filtersByStatus() {
    insertIssue("u", "error", "unresolved", Instant.now());
    insertIssue("r", "error", "resolved", Instant.now().minusSeconds(60));
    insertIssue("i", "error", "ignored", Instant.now().minusSeconds(120));

    List<Issue> resolved =
        repository.page(
            101L,
            Optional.of(Issue.Status.RESOLVED),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            10);
    assertThat(resolved).extracting(Issue::fingerprint).containsExactly("r");
  }

  @Test
  void filtersByLevel() {
    insertIssue("err", "error", "unresolved", Instant.now());
    insertIssue("warn", "warning", "unresolved", Instant.now().minusSeconds(60));

    List<Issue> warnings =
        repository.page(
            101L,
            Optional.empty(),
            Optional.of(Issue.Level.WARNING),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            10);
    assertThat(warnings).extracting(Issue::fingerprint).containsExactly("warn");
  }

  @Test
  void cursorSeeksStrictlyPastTheGivenTuple() {
    Instant t = Instant.parse("2026-05-05T12:00:00Z");
    insertIssue("a", "error", "unresolved", t);
    insertIssue("b", "error", "unresolved", t); // same timestamp; tiebreak by id desc
    insertIssue("c", "error", "unresolved", t);

    List<Issue> first =
        repository.page(
            101L,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            2);
    assertThat(first).hasSize(2);
    Issue last = first.get(1);

    List<Issue> next =
        repository.page(
            101L,
            Optional.empty(),
            Optional.empty(),
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
        repository.page(
            101L,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            3);
    assertThat(page).hasSize(3);
  }

  @Test
  void filtersBySearchTextOnTitle() {
    insertIssue("login-bug", "error", "unresolved", Instant.now(), 101L, "Login failed");
    insertIssue("checkout-bug", "error", "unresolved", Instant.now(), 101L, "Checkout error");

    List<Issue> match =
        repository.page(
            101L,
            Optional.empty(),
            Optional.empty(),
            Optional.of("login"),
            Optional.empty(),
            Optional.empty(),
            10);
    assertThat(match).extracting(Issue::fingerprint).containsExactly("login-bug");
  }

  @Test
  void filtersBySearchTextCaseInsensitive() {
    insertIssue("a", "error", "unresolved", Instant.now(), 101L, "RareWord here");

    List<Issue> match =
        repository.page(
            101L,
            Optional.empty(),
            Optional.empty(),
            Optional.of("rareWORD"),
            Optional.empty(),
            Optional.empty(),
            10);
    assertThat(match).hasSize(1);
  }

  @Test
  void filtersByAssigneeUuid() {
    java.util.UUID alice = java.util.UUID.randomUUID();
    java.util.UUID bob = java.util.UUID.randomUUID();
    insertUser(alice, "alice@example.com");
    insertUser(bob, "bob@example.com");
    insertIssueWithAssignee("a", alice);
    insertIssueWithAssignee("b", bob);
    insertIssueWithAssignee("u", null);

    List<Issue> mine =
        repository.page(
            101L,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(
                new org.arguslog.api.application.ListIssuesUseCase.AssigneeFilter.User(alice)),
            Optional.empty(),
            10);
    assertThat(mine).extracting(Issue::fingerprint).containsExactly("a");
  }

  @Test
  void filtersByAssigneeUnassigned() {
    java.util.UUID alice = java.util.UUID.randomUUID();
    insertUser(alice, "alice2@example.com");
    insertIssueWithAssignee("with-owner", alice);
    insertIssueWithAssignee("ownerless", null);

    List<Issue> unassigned =
        repository.page(
            101L,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(
                org.arguslog.api.application.ListIssuesUseCase.AssigneeFilter.UNASSIGNED),
            Optional.empty(),
            10);
    assertThat(unassigned).extracting(Issue::fingerprint).containsExactly("ownerless");
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private void insertIssue(String fingerprint, String level, String status, Instant lastSeen) {
    insertIssue(fingerprint, level, status, lastSeen, 101L);
  }

  private void insertIssue(
      String fingerprint, String level, String status, Instant lastSeen, long projectId) {
    insertIssue(fingerprint, level, status, lastSeen, projectId, "Title for " + fingerprint);
  }

  private void insertIssue(
      String fingerprint,
      String level,
      String status,
      Instant lastSeen,
      long projectId,
      String title) {
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
      stmt.setString(5, title);
      stmt.setTimestamp(6, java.sql.Timestamp.from(lastSeen));
      stmt.setTimestamp(7, java.sql.Timestamp.from(lastSeen));
      stmt.execute();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void insertIssueWithAssignee(String fingerprint, java.util.UUID assignee) {
    String sql =
        """
        INSERT INTO issues (project_id, environment_id, fingerprint, status, level, title,
                            culprit, first_seen_at, last_seen_at, occurrence_count,
                            assignee_user_id)
             VALUES (101, NULL, ?, 'unresolved'::issue_status, 'error'::event_level,
                     'Title for ' || ?, NULL, NOW(), NOW(), 1, ?)
        """;
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, fingerprint);
      stmt.setString(2, fingerprint);
      if (assignee == null) {
        stmt.setNull(3, java.sql.Types.OTHER);
      } else {
        stmt.setObject(3, assignee);
      }
      stmt.execute();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void insertUser(java.util.UUID id, String email) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement("INSERT INTO users (id, email) VALUES (?, ?)")) {
      stmt.setObject(1, id);
      stmt.setString(2, email);
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
