package org.arguslog.api.alerts.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.alerts.application.port.AlertDestinationRepository;
import org.arguslog.api.alerts.application.port.AlertDestinationWriteRepository;
import org.arguslog.api.alerts.domain.AlertDestination;
import org.arguslog.api.alerts.domain.DestinationKind;
import org.arguslog.api.security.OrgContext;
import org.arguslog.crypto.AesGcmSecretCipher;
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
class JdbcAlertDestinationRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static AlertDestinationRepository repository;
  private static AlertDestinationWriteRepository writes;

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
    JdbcAlertDestinationRepository raw =
        new JdbcAlertDestinationRepository(
            dataSource,
            new AesGcmSecretCipher(
                java.util.Base64.getEncoder()
                    .encodeToString("the-key-must-be-32-bytes-long!!!".getBytes())));
    // Wrap so SET LOCAL inside pinOrgContextForRls scopes to the call's
    // transaction.
    repository =
        new AlertDestinationRepository() {
          @Override
          public List<AlertDestination> listForOrg(long orgId) {
            return tx.execute(s -> raw.listForOrg(orgId));
          }

          @Override
          public Optional<AlertDestination> find(long orgId, long id) {
            return tx.execute(s -> raw.find(orgId, id));
          }
        };
    writes =
        new AlertDestinationWriteRepository() {
          @Override
          public AlertDestination create(
              long orgId, DestinationKind kind, String name, String configJson) {
            return tx.execute(s -> raw.create(orgId, kind, name, configJson));
          }

          @Override
          public Optional<AlertDestination> update(
              long orgId, long id, String name, String configJson) {
            return tx.execute(s -> raw.update(orgId, id, name, configJson));
          }

          @Override
          public Optional<AlertDestination> setEnabled(long orgId, long id, boolean enabled) {
            return tx.execute(s -> raw.setEnabled(orgId, id, enabled));
          }

          @Override
          public boolean delete(long orgId, long id) {
            return Boolean.TRUE.equals(tx.execute(s -> raw.delete(orgId, id)));
          }
        };
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void prime() throws Exception {
    OrgContext.set(1L);
    try (Connection conn = dataSource.getConnection()) {
      exec(conn, "TRUNCATE alert_destinations RESTART IDENTITY CASCADE");
    }
  }

  @AfterEach
  void clear() {
    OrgContext.clear();
  }

  @Test
  void roundTripsConfigThroughEncryption() {
    AlertDestination created =
        writes.create(1L, DestinationKind.TELEGRAM, "ops", "{\"chatId\":\"-100\"}");
    AlertDestination loaded = repository.find(1L, created.id()).orElseThrow();
    assertThat(loaded.configJson()).isEqualTo("{\"chatId\":\"-100\"}");
    assertThat(loaded.kind()).isEqualTo(DestinationKind.TELEGRAM);
    assertThat(loaded.name()).isEqualTo("ops");
  }

  @Test
  void persistsCiphertextNotPlaintextInTheDb() throws Exception {
    writes.create(1L, DestinationKind.WEBHOOK, "ci", "{\"url\":\"https://example.com/hook\"}");
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement("SELECT config_encrypted FROM alert_destinations LIMIT 1");
        var rs = stmt.executeQuery()) {
      rs.next();
      byte[] raw = rs.getBytes(1);
      String asText = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1);
      // The plaintext URL must not appear in the stored bytes — the cipher actually
      // wraps it.
      assertThat(asText).doesNotContain("example.com");
      assertThat(raw[0]).isEqualTo((byte) 1); // version byte
    }
  }

  @Test
  void listOrdersByCreatedDescId() throws InterruptedException {
    AlertDestination first =
        writes.create(1L, DestinationKind.WEBHOOK, "a", "{\"url\":\"https://1\"}");
    Thread.sleep(5);
    AlertDestination second =
        writes.create(1L, DestinationKind.WEBHOOK, "b", "{\"url\":\"https://2\"}");

    List<AlertDestination> page = repository.listForOrg(1L);
    assertThat(page).extracting(AlertDestination::id).containsExactly(second.id(), first.id());
  }

  @Test
  void updateOnlyTouchesTheRequestedRow() {
    AlertDestination kept =
        writes.create(1L, DestinationKind.WEBHOOK, "kept", "{\"url\":\"https://k\"}");
    AlertDestination edited =
        writes.create(1L, DestinationKind.WEBHOOK, "edited", "{\"url\":\"https://e\"}");

    Optional<AlertDestination> updated =
        writes.update(1L, edited.id(), "renamed", "{\"url\":\"https://e2\"}");

    assertThat(updated).isPresent();
    assertThat(updated.orElseThrow().name()).isEqualTo("renamed");
    assertThat(updated.orElseThrow().configJson()).isEqualTo("{\"url\":\"https://e2\"}");
    assertThat(repository.find(1L, kept.id()).orElseThrow().name()).isEqualTo("kept");
  }

  @Test
  void deleteIsAccountedFor() {
    AlertDestination created =
        writes.create(1L, DestinationKind.WEBHOOK, "k", "{\"url\":\"https://k\"}");
    assertThat(writes.delete(1L, created.id())).isTrue();
    assertThat(writes.delete(1L, created.id())).isFalse();
    assertThat(repository.find(1L, created.id())).isEmpty();
  }

  @Test
  void wrongOrgCannotSeeOrMutate() {
    AlertDestination created =
        writes.create(1L, DestinationKind.WEBHOOK, "k", "{\"url\":\"https://k\"}");
    assertThat(repository.find(2L, created.id())).isEmpty();
    assertThat(writes.update(2L, created.id(), "x", "{\"url\":\"https://x\"}")).isEmpty();
    assertThat(writes.delete(2L, created.id())).isFalse();
  }

  private static void seed(DataSource ds) throws Exception {
    try (Connection conn = ds.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme')");
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (2, 'other', 'Other')");
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
