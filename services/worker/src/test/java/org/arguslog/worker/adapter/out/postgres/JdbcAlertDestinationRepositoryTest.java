package org.arguslog.worker.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.arguslog.crypto.AesGcmSecretCipher;
import org.arguslog.crypto.SecretCipher;
import org.arguslog.worker.application.port.AlertDestinationRepository;
import org.arguslog.worker.domain.AlertDestination;
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
  private static SecretCipher cipher;

  @BeforeAll
  static void boot() {
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
    seed(dataSource);
    cipher = new AesGcmSecretCipher(""); // dev fallback key
    repository = new JdbcAlertDestinationRepository(dataSource, cipher);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void clean() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      exec(conn, "TRUNCATE alert_destinations RESTART IDENTITY CASCADE");
    }
  }

  @Test
  void decryptsConfigAndPreservesInputOrder() throws Exception {
    long a = insertDestination(1L, "telegram", "ops-chat", "{\"chatId\":\"-1001\"}");
    long b =
        insertDestination(1L, "slack", "alerts", "{\"webhookUrl\":\"https://hook.example/x\"}");

    List<AlertDestination> ordered = repository.findAllById(List.of(b, a));

    assertThat(ordered).extracting(AlertDestination::id).containsExactly(b, a);
    assertThat(ordered.get(0).kind()).isEqualTo(AlertDestination.Kind.SLACK);
    assertThat(ordered.get(1).kind()).isEqualTo(AlertDestination.Kind.TELEGRAM);
    assertThat(ordered.get(0).configJson()).contains("hook.example");
    assertThat(ordered.get(1).configJson()).contains("\"chatId\":\"-1001\"");
  }

  @Test
  void emptyInputReturnsEmpty() {
    assertThat(repository.findAllById(List.of())).isEmpty();
  }

  @Test
  void unknownIdsAreSilentlyDropped() throws Exception {
    long a = insertDestination(1L, "telegram", "ops", "{\"chatId\":\"42\"}");
    List<AlertDestination> result = repository.findAllById(List.of(a, 9999L));
    assertThat(result).hasSize(1);
    assertThat(result.get(0).id()).isEqualTo(a);
  }

  @Test
  void corruptedCiphertextIsDroppedNotThrown() throws Exception {
    long a = insertDestination(1L, "telegram", "ops", "{\"chatId\":\"42\"}");
    long bad = insertCorruptDestination(1L, "telegram", "broken");

    List<AlertDestination> result = repository.findAllById(List.of(a, bad));

    assertThat(result).extracting(AlertDestination::id).containsExactly(a);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

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

  private static void seed(DataSource ds) {
    try (Connection conn = ds.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme')");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static long insertDestination(long orgId, String kind, String name, String configJson)
      throws Exception {
    byte[] ciphertext = encryptWithDevKey(configJson.getBytes(StandardCharsets.UTF_8));
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO alert_destinations (org_id, kind, name, config_encrypted)"
                    + " VALUES (?, ?::destination_kind, ?, ?) RETURNING id")) {
      stmt.setLong(1, orgId);
      stmt.setString(2, kind);
      stmt.setString(3, name);
      stmt.setBytes(4, ciphertext);
      try (var rs = stmt.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static long insertCorruptDestination(long orgId, String kind, String name)
      throws Exception {
    // Looks like ciphertext (right length) but the auth tag won't validate.
    byte[] junk = new byte[1 + 12 + 32];
    new SecureRandom().nextBytes(junk);
    junk[0] = 1; // pretend version 1 so the version check passes
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO alert_destinations (org_id, kind, name, config_encrypted)"
                    + " VALUES (?, ?::destination_kind, ?, ?) RETURNING id")) {
      stmt.setLong(1, orgId);
      stmt.setString(2, kind);
      stmt.setString(3, name);
      stmt.setBytes(4, junk);
      try (var rs = stmt.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  // Mirror of the dev-fallback key in AesGcmSecretCipher so we can produce
  // wire-compatible bytes
  // without depending on the api module from worker tests.
  private static byte[] encryptWithDevKey(byte[] plaintext) throws Exception {
    SecretKeySpec key =
        new SecretKeySpec(
            "arguslog-dev-fallback-key-32byte".getBytes(StandardCharsets.UTF_8), "AES");
    byte[] iv = new byte[12];
    new SecureRandom().nextBytes(iv);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
    byte[] ct = c.doFinal(plaintext);
    byte[] out = new byte[1 + 12 + ct.length];
    out[0] = 1;
    System.arraycopy(iv, 0, out, 1, 12);
    System.arraycopy(ct, 0, out, 13, ct.length);
    return out;
  }

  private static void exec(Connection conn, String sql) {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
