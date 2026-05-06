package org.arguslog.worker.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.arguslog.ingest.adapter.out.auth.PostgresProjectAuthenticator;
import org.arguslog.ingest.adapter.out.quota.AllowAllQuotaEnforcer;
import org.arguslog.ingest.adapter.out.redis.RedisStreamEventPublisher;
import org.arguslog.ingest.application.IngestEventService;
import org.arguslog.ingest.application.IngestEventUseCase;
import org.arguslog.ingest.application.IngestEventUseCase.Command;
import org.arguslog.ingest.application.IngestEventUseCase.Result;
import org.arguslog.worker.adapter.in.redis.RedisStreamEventListener;
import org.arguslog.worker.adapter.in.redis.RedisStreamProperties;
import org.arguslog.worker.adapter.out.fingerprint.PayloadFingerprinter;
import org.arguslog.worker.adapter.out.postgres.JdbcEventStore;
import org.arguslog.worker.application.ProcessEventService;
import org.arguslog.worker.application.ProcessEventUseCase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * P1 milestone #4 — exercises the full Java ingest → Redis → worker → Postgres chain in a single
 * JVM, against real TimescaleDB and Redis containers. The SDK ↔ ingest HTTP wire format is covered
 * separately (Pact contract tests) so this stays focused on the back-end pipeline.
 */
@Testcontainers
class IngestToPostgresEndToEndTest {

  private static final String STREAM_KEY = "events:incoming";
  private static final String CONSUMER_GROUP = "worker";
  private static final String DSN_PUBLIC = "e2e-public-key";
  private static final long PROJECT_ID = 101L;

  private static final DockerImageName TIMESCALE_IMAGE =
      DockerImageName.parse("timescale/timescaledb:latest-pg16")
          .asCompatibleSubstituteFor("postgres");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TIMESCALE_IMAGE)
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static HikariDataSource dataSource;
  private static LettuceConnectionFactory redisFactory;
  private static StringRedisTemplate redis;
  private static StreamMessageListenerContainer<String, MapRecord<String, String, String>>
      listenerContainer;
  private static IngestEventUseCase ingest;

  @BeforeAll
  static void boot() throws Exception {
    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(POSTGRES.getJdbcUrl());
    hikari.setUsername(POSTGRES.getUsername());
    hikari.setPassword(POSTGRES.getPassword());
    dataSource = new HikariDataSource(hikari);

    Flyway.configure()
        .dataSource(dataSource)
        .locations(resolveMigrationsLocation())
        .load()
        .migrate();
    seed(dataSource);

    redisFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    redisFactory.afterPropertiesSet();
    redis = new StringRedisTemplate(redisFactory);
    redis.afterPropertiesSet();

    // Worker side --------------------------------------------------------
    JdbcEventStore rawStore = new JdbcEventStore(dataSource);
    TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    // Alerts pipeline + symbolicator are exercised in their own tests; here we wire
    // pass-through no-ops so this test stays focused on the ingest → Redis → worker → Postgres
    // path.
    ProcessEventService unwrapped =
        new ProcessEventService(
            new PayloadFingerprinter(new ObjectMapper()),
            rawStore,
            persisted -> {},
            (projectId, payload) -> payload);
    ProcessEventUseCase wrapped = event -> tx.execute(status -> unwrapped.process(event));

    RedisStreamProperties props =
        new RedisStreamProperties(
            STREAM_KEY, CONSUMER_GROUP, "e2e-worker", 50, Duration.ofMillis(200));
    RedisStreamEventListener listener = new RedisStreamEventListener(wrapped, redis, props);

    redis.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);

    listenerContainer =
        StreamMessageListenerContainer.create(
            redisFactory,
            StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofMillis(200))
                .batchSize(50)
                .build());
    listenerContainer.receive(
        Consumer.from(CONSUMER_GROUP, "e2e-worker"),
        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()),
        listener);
    listenerContainer.start();

    // Ingest side --------------------------------------------------------
    ingest =
        new IngestEventService(
            new PostgresProjectAuthenticator(dataSource),
            new AllowAllQuotaEnforcer(),
            new RedisStreamEventPublisher(redis, STREAM_KEY),
            Clock.systemUTC());
  }

  @AfterAll
  static void stop() {
    if (listenerContainer != null) listenerContainer.stop();
    if (redisFactory != null) redisFactory.destroy();
    if (dataSource != null) dataSource.close();
  }

  @AfterEach
  void cleanRows() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      exec(conn, "TRUNCATE events, issues RESTART IDENTITY CASCADE");
    }
  }

  @Test
  void typeErrorEventLandsInPostgresWithMatchingIssue() {
    String payload =
        """
        {"level":"error","exception":{"values":[
          {"type":"TypeError","value":"x is undefined",
           "stacktrace":{"frames":[{"filename":"app.js","function":"render","lineno":42}]}}
        ]}}
        """;

    Result result =
        ingest.ingest(new Command(PROJECT_ID, DSN_PUBLIC, payload, "127.0.0.1", "junit"));
    assertThat(result).isInstanceOf(Result.Accepted.class);

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(countEvents()).isEqualTo(1L));

    EventRow row = lastEventRow();
    assertThat(row.projectId).isEqualTo(PROJECT_ID);
    assertThat(row.payload).contains("TypeError").contains("x is undefined");

    IssueRow issue = issueFor(row.issueId);
    assertThat(issue.title).startsWith("TypeError");
    assertThat(issue.culprit).isEqualTo("render at app.js:42");
    assertThat(issue.occurrenceCount).isEqualTo(1L);
    assertThat(issue.level).isEqualTo("error");
  }

  @Test
  void twoEventsWithSameSignatureLandUnderTheSameIssue() {
    String payload =
        """
        {"level":"warning","exception":{"values":[
          {"type":"NetworkError","value":"timeout"}
        ]}}
        """;

    ingest.ingest(new Command(PROJECT_ID, DSN_PUBLIC, payload, "127.0.0.1", "junit"));
    ingest.ingest(new Command(PROJECT_ID, DSN_PUBLIC, payload, "127.0.0.1", "junit"));

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(countEvents()).isEqualTo(2L));

    assertThat(countIssues()).isEqualTo(1L);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static long countEvents() {
    return queryLong("SELECT COUNT(*) FROM events");
  }

  private static long countIssues() {
    return queryLong("SELECT COUNT(*) FROM issues");
  }

  private static long queryLong(String sql) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql);
        ResultSet rs = stmt.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static EventRow lastEventRow() {
    String sql =
        "SELECT issue_id, project_id, payload::text FROM events ORDER BY received_at DESC LIMIT 1";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql);
        ResultSet rs = stmt.executeQuery()) {
      rs.next();
      return new EventRow(rs.getLong(1), rs.getLong(2), rs.getString(3));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static IssueRow issueFor(long issueId) {
    String sql = "SELECT title, culprit, level::text, occurrence_count FROM issues WHERE id = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setLong(1, issueId);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return new IssueRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4));
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private record EventRow(long issueId, long projectId, String payload) {}

  private record IssueRow(String title, String culprit, String level, long occurrenceCount) {}

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

  private static void seed(DataSource ds) throws Exception {
    try (Connection conn = ds.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES ("
              + PROJECT_ID
              + ", 1, 'web', 'Web', 'javascript')");
      try (PreparedStatement stmt =
          conn.prepareStatement(
              "INSERT INTO project_keys (project_id, dsn_public, dsn_secret_hash, active) VALUES (?, ?, NULL, TRUE)")) {
        stmt.setLong(1, PROJECT_ID);
        stmt.setString(2, DSN_PUBLIC);
        stmt.execute();
      }
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
