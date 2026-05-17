package org.arguslog.api.slack.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.security.OrgContext;
import org.arguslog.api.slack.application.port.SlackWorkspaceRepository;
import org.arguslog.api.slack.application.port.SlackWorkspaceWriteRepository;
import org.arguslog.api.slack.domain.SlackWorkspace;
import org.arguslog.crypto.SecretCipher;
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
class JdbcSlackWorkspaceRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static SlackWorkspaceRepository reads;
  private static SlackWorkspaceWriteRepository writes;

  /**
   * Identity cipher — test-only; the real implementation lives in lib/crypto-aes-gcm. Using a stub
   * keeps the test focused on the SQL + plaintext-roundtrip contract; the cipher's own tests cover
   * the AES-GCM correctness.
   */
  private static final SecretCipher IDENTITY_CIPHER =
      new SecretCipher() {
        @Override
        public byte[] encrypt(byte[] plaintext) {
          return plaintext;
        }

        @Override
        public byte[] decrypt(byte[] ciphertext) {
          return ciphertext;
        }
      };

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
    JdbcSlackWorkspaceRepository raw =
        new JdbcSlackWorkspaceRepository(dataSource, IDENTITY_CIPHER);
    reads =
        new SlackWorkspaceRepository() {
          @Override
          public Optional<SlackWorkspace> findActiveByTeamId(String slackTeamId) {
            return tx.execute(s -> raw.findActiveByTeamId(slackTeamId));
          }

          @Override
          public List<SlackWorkspace> listForOrg(long orgId) {
            return tx.execute(s -> raw.listForOrg(orgId));
          }
        };
    writes =
        new SlackWorkspaceWriteRepository() {
          @Override
          public SlackWorkspace upsert(
              String slackTeamId,
              String slackTeamName,
              String installToken,
              long orgId,
              Long defaultProjectId,
              UUID installedByUserId,
              String webhookUrl,
              String webhookChannel) {
            return tx.execute(
                s ->
                    raw.upsert(
                        slackTeamId,
                        slackTeamName,
                        installToken,
                        orgId,
                        defaultProjectId,
                        installedByUserId,
                        webhookUrl,
                        webhookChannel));
          }

          @Override
          public void deactivate(long workspaceId) {
            tx.execute(
                s -> {
                  raw.deactivate(workspaceId);
                  return null;
                });
          }

          @Override
          public SlackWorkspace setDefaultProject(long workspaceId, Long defaultProjectId) {
            return tx.execute(s -> raw.setDefaultProject(workspaceId, defaultProjectId));
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
      exec(conn, "TRUNCATE slack_workspaces RESTART IDENTITY CASCADE");
    }
  }

  @AfterEach
  void clear() {
    OrgContext.clear();
  }

  @Test
  void upsertInsertsThenRoundTripsTheToken() {
    SlackWorkspace row =
        writes.upsert(
            "T123",
            "Acme Workspace",
            "xoxb-fake-token",
            1L,
            101L,
            null,
            "https://hooks.slack.com/services/T/B/secret",
            "#alerts");

    assertThat(row.id()).isPositive();
    assertThat(row.slackTeamId()).isEqualTo("T123");
    assertThat(row.installToken()).isEqualTo("xoxb-fake-token");
    assertThat(row.orgId()).isEqualTo(1L);
    assertThat(row.defaultProjectId()).isEqualTo(101L);
    assertThat(row.deactivatedAt()).isNull();
    // Webhook URL cipher round-trips end-to-end, channel passes through plain.
    assertThat(row.webhookUrl()).isEqualTo("https://hooks.slack.com/services/T/B/secret");
    assertThat(row.webhookChannel()).isEqualTo("#alerts");
    assertThat(row.hasWebhook()).isTrue();
  }

  @Test
  void upsertOnConflictRotatesTheTokenAndReactivates() {
    SlackWorkspace first = writes.upsert("T123", "Acme", "xoxb-old", 1L, null, null, null, null);
    writes.deactivate(first.id());

    SlackWorkspace reinstall =
        writes.upsert("T123", "Acme Renamed", "xoxb-new", 1L, 101L, null, null, null);

    assertThat(reinstall.id()).isEqualTo(first.id());
    assertThat(reinstall.slackTeamName()).isEqualTo("Acme Renamed");
    assertThat(reinstall.installToken()).isEqualTo("xoxb-new");
    assertThat(reinstall.defaultProjectId()).isEqualTo(101L);
    assertThat(reinstall.deactivatedAt()).isNull();
  }

  @Test
  void findActiveByTeamIdMissesDeactivatedRows() {
    SlackWorkspace row = writes.upsert("T123", "Acme", "tok", 1L, null, null, null, null);
    assertThat(reads.findActiveByTeamId("T123")).isPresent();

    writes.deactivate(row.id());
    assertThat(reads.findActiveByTeamId("T123")).isEmpty();
    assertThat(reads.findActiveByTeamId("T999")).isEmpty();
  }

  @Test
  void setDefaultProjectUpdatesTheRow() {
    SlackWorkspace row = writes.upsert("T123", "Acme", "tok", 1L, null, null, null, null);
    assertThat(row.defaultProjectId()).isNull();

    SlackWorkspace updated = writes.setDefaultProject(row.id(), 102L);
    assertThat(updated.defaultProjectId()).isEqualTo(102L);

    // Clearing back to null also supported (use case: project deleted; operator picks another).
    SlackWorkspace cleared = writes.setDefaultProject(row.id(), null);
    assertThat(cleared.defaultProjectId()).isNull();
  }

  @Test
  void listForOrgRespectsRlsAndIncludesDeactivatedRows() {
    SlackWorkspace alive = writes.upsert("T1", "Alive", "tok1", 1L, null, null, null, null);
    SlackWorkspace dead = writes.upsert("T2", "Dead", "tok2", 1L, null, null, null, null);
    writes.deactivate(dead.id());

    List<SlackWorkspace> rows = reads.listForOrg(1L);
    // listForOrg is the dashboard view → returns both alive and revoked rows; UI filters as
    // needed. This mirrors the DSN listAllForProject pattern.
    assertThat(rows).extracting(SlackWorkspace::slackTeamId).containsExactly("T2", "T1");
    assertThat(rows.stream().filter(SlackWorkspace::isActive).count()).isEqualTo(1L);
    assertThat(alive.installToken()).isEqualTo("tok1"); // cipher round-trip stayed identity
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static void seed(DataSource ds) throws Exception {
    try (Connection conn = ds.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (101, 1, 'web', 'Web', 'javascript')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (102, 1, 'api', 'Api', 'java')");
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
