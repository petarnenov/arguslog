package org.arguslog.api.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.application.CursorCodec.UuidCursor;
import org.arguslog.api.application.port.EventRepository;
import org.arguslog.api.domain.Event;
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
class JdbcEventRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
      DockerImageName.parse("timescale/timescaledb:latest-pg16")
          .asCompatibleSubstituteFor("postgres"))
      .withDatabaseName("arguslog")
      .withUsername("arguslog")
      .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static EventRepository repository;
  private static long issueIdA;
  private static long issueIdB;

  @BeforeAll
  static void boot() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    seed(dataSource);

    TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    JdbcEventRepository raw = new JdbcEventRepository(dataSource, new ObjectMapper());
    repository = (issueId, cursor, limit) -> tx.execute(s -> raw.page(issueId, cursor, limit));

    issueIdA = insertIssue("fp-a");
    issueIdB = insertIssue("fp-b");
  }

  @AfterAll
  static void stop() {
    if (dataSource != null)
      dataSource.close();
  }

  @BeforeEach
  void prime() throws Exception {
    OrgContext.set(1L);
    try (Connection conn = dataSource.getConnection()) {
      exec(conn, "TRUNCATE events");
    }
  }

  @AfterEach
  void clear() {
    OrgContext.clear();
  }

  @Test
  void returnsEventsForOnlyTheGivenIssueOrderedByReceivedAtDesc() {
    insertEvent(issueIdA, Instant.parse("2026-05-05T10:00:00Z"));
    insertEvent(issueIdA, Instant.parse("2026-05-05T12:00:00Z"));
    insertEvent(issueIdA, Instant.parse("2026-05-05T11:00:00Z"));
    insertEvent(issueIdB, Instant.parse("2026-05-05T13:00:00Z")); // other issue, must not appear

    List<Event> page = repository.page(issueIdA, Optional.empty(), 10);

    assertThat(page).hasSize(3);
    assertThat(page)
        .extracting(Event::receivedAt)
        .containsExactly(
            Instant.parse("2026-05-05T12:00:00Z"),
            Instant.parse("2026-05-05T11:00:00Z"),
            Instant.parse("2026-05-05T10:00:00Z"));
  }

  @Test
  void respectsLimit() {
    for (int i = 0; i < 5; i++) {
      insertEvent(issueIdA, Instant.parse("2026-05-05T12:00:00Z").plusSeconds(i));
    }
    assertThat(repository.page(issueIdA, Optional.empty(), 3)).hasSize(3);
  }

  @Test
  void cursorSeeksStrictlyPastTheTuple() {
    insertEvent(issueIdA, Instant.parse("2026-05-05T12:00:00Z"));
    insertEvent(issueIdA, Instant.parse("2026-05-05T12:00:00Z")); // same ts; tiebreak on id
    insertEvent(issueIdA, Instant.parse("2026-05-05T12:00:00Z"));

    List<Event> first = repository.page(issueIdA, Optional.empty(), 2);
    assertThat(first).hasSize(2);
    Event last = first.get(1);

    List<Event> next = repository.page(issueIdA, Optional.of(new UuidCursor(last.receivedAt(), last.id())), 10);
    assertThat(next).hasSize(1);
    assertThat(next.get(0).id()).isNotEqualTo(last.id());
  }

  @Test
  void payloadJsonIsParsedThrough() {
    insertEvent(
        issueIdA,
        Instant.parse("2026-05-05T12:00:00Z"),
        "{\"level\":\"warning\",\"message\":\"hi\"}");
    Event e = repository.page(issueIdA, Optional.empty(), 1).get(0);
    assertThat(e.payload().path("level").asText()).isEqualTo("warning");
    assertThat(e.payload().path("message").asText()).isEqualTo("hi");
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static long insertIssue(String fingerprint) {
    String sql = """
        INSERT INTO issues (project_id, environment_id, fingerprint, status, level, title,
                            culprit, first_seen_at, last_seen_at, occurrence_count)
             VALUES (101, NULL, ?, 'unresolved'::issue_status, 'error'::event_level, ?, NULL, NOW(), NOW(), 1)
        RETURNING id
        """;
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, fingerprint);
      stmt.setString(2, "Title for " + fingerprint);
      var rs = stmt.executeQuery();
      rs.next();
      return rs.getLong(1);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void insertEvent(long issueId, Instant receivedAt) {
    insertEvent(issueId, receivedAt, "{\"level\":\"error\"}");
  }

  private void insertEvent(long issueId, Instant receivedAt, String payload) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO events (id, issue_id, project_id, environment_id, received_at, payload) VALUES (?, ?, 101, NULL, ?, ?::jsonb)")) {
      stmt.setObject(1, UUID.randomUUID());
      stmt.setLong(2, issueId);
      stmt.setTimestamp(3, java.sql.Timestamp.from(receivedAt));
      stmt.setString(4, payload);
      stmt.execute();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void seed(DataSource ds) throws Exception {
    try (Connection conn = ds.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (101, 1, 'web', 'Web', 'javascript')");
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
