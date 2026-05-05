package org.arguslog.worker.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.worker.application.port.EventStore;
import org.arguslog.worker.application.port.EventStore.PersistResult;
import org.arguslog.worker.domain.Fingerprint;
import org.arguslog.worker.domain.IncomingEvent;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class JdbcEventStoreTest {

  private static final DockerImageName TIMESCALE_IMAGE =
      DockerImageName.parse("timescale/timescaledb:latest-pg16")
          .asCompatibleSubstituteFor("postgres");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TIMESCALE_IMAGE)
          .withDatabaseName("argus")
          .withUsername("argus")
          .withPassword("argus");

  private static HikariDataSource dataSource;
  private static EventStore store;

  @BeforeAll
  static void migrateAndSeed() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(config);

    Flyway.configure()
        .dataSource(dataSource)
        .locations(resolveMigrationsLocation())
        .load()
        .migrate();
    seedProject(dataSource);

    PlatformTransactionManager txm =
        new org.springframework.jdbc.support.JdbcTransactionManager(dataSource);
    JdbcEventStore raw = new JdbcEventStore(dataSource);
    // Wrap in a TransactionTemplate so the @Transactional contract is honored without
    // bringing the full Spring context up.
    TransactionTemplate template = new TransactionTemplate(txm);
    store = (event, fingerprint) -> template.execute(status -> raw.persist(event, fingerprint));
  }

  @AfterAll
  static void tearDown() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @AfterEach
  void cleanRows() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      exec(conn, "TRUNCATE events, issues RESTART IDENTITY CASCADE");
    }
  }

  @Test
  void firstEventCreatesIssueAndInsertsRow() throws Exception {
    IncomingEvent event = sampleEvent();
    Fingerprint fp = fingerprint("hash-1");

    PersistResult result = store.persist(event, fp);

    assertThat(result.newIssue()).isTrue();
    assertThat(result.issueId()).isPositive();
    assertThat(countEvents()).isEqualTo(1);
    assertThat(occurrenceCount(result.issueId())).isEqualTo(1L);
  }

  @Test
  void secondEventBumpsExistingIssueAndInsertsRow() {
    Fingerprint fp = fingerprint("hash-shared");
    PersistResult first = store.persist(sampleEvent(), fp);
    PersistResult second = store.persist(sampleEvent(), fp);

    assertThat(first.newIssue()).isTrue();
    assertThat(second.newIssue()).isFalse();
    assertThat(second.issueId()).isEqualTo(first.issueId());
    assertThat(occurrenceCount(first.issueId())).isEqualTo(2L);
    assertThat(countEvents()).isEqualTo(2);
  }

  @Test
  void duplicateEventIdIsIdempotent() {
    Fingerprint fp = fingerprint("hash-dupe");
    UUID eventId = UUID.randomUUID();
    Instant t = Instant.parse("2026-05-05T12:00:00Z");
    IncomingEvent event = new IncomingEvent(eventId, 101L, "pk", t, "{}", "ip", "ua");

    PersistResult first = store.persist(event, fp);
    // Same event id + same time — Redis redelivery scenario.
    PersistResult second = store.persist(event, fp);

    assertThat(first.issueId()).isEqualTo(second.issueId());
    // events table swallows the duplicate; issues row gets bumped twice though
    // (occurrence_count increments on every persist call). De-dup at the consumer
    // layer (XACK on success) is what protects occurrence_count in production.
    assertThat(countEvents()).isEqualTo(1);
  }

  @Test
  void differentFingerprintsCreateDistinctIssues() {
    Fingerprint a = fingerprint("hash-a");
    Fingerprint b = fingerprint("hash-b");
    PersistResult ra = store.persist(sampleEvent(), a);
    PersistResult rb = store.persist(sampleEvent(), b);
    assertThat(ra.issueId()).isNotEqualTo(rb.issueId());
    assertThat(countEvents()).isEqualTo(2);
  }

  @Test
  void issueRowCarriesTitleCulpritAndLevel() {
    Fingerprint fp =
        new Fingerprint(
            "hash-meta", "TypeError: x", "main at app.js:42", Fingerprint.Level.WARNING);
    PersistResult result = store.persist(sampleEvent(), fp);

    var meta = issueMeta(result.issueId());
    assertThat(meta.title).isEqualTo("TypeError: x");
    assertThat(meta.culprit).isEqualTo("main at app.js:42");
    assertThat(meta.level).isEqualTo("warning");
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static IncomingEvent sampleEvent() {
    return new IncomingEvent(
        UUID.randomUUID(),
        101L,
        "pk",
        Instant.parse("2026-05-05T12:00:00Z"),
        "{\"level\":\"error\"}",
        "127.0.0.1",
        "JUnit");
  }

  private static Fingerprint fingerprint(String hash) {
    return new Fingerprint(hash, "Title for " + hash, null, Fingerprint.Level.ERROR);
  }

  private long countEvents() {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM events");
        ResultSet rs = stmt.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private long occurrenceCount(long issueId) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement("SELECT occurrence_count FROM issues WHERE id = ?")) {
      stmt.setLong(1, issueId);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private IssueMeta issueMeta(long issueId) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement("SELECT title, culprit, level::text FROM issues WHERE id = ?")) {
      stmt.setLong(1, issueId);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return new IssueMeta(rs.getString(1), rs.getString(2), rs.getString(3));
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private record IssueMeta(String title, String culprit, String level) {}

  private static String resolveMigrationsLocation() {
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

  private static void seedProject(DataSource ds) throws Exception {
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
