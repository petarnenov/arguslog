package org.arguslog.worker.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.worker.application.port.SymbolicationRepository;
import org.arguslog.worker.application.port.SymbolicationRepository.ArtifactRow;
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
class JdbcSymbolicationRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static SymbolicationRepository repository;

  @BeforeAll
  static void boot() throws Exception {
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
    repository = new JdbcSymbolicationRepository(dataSource);
  }

  @AfterAll
  static void stop() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void clean() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      exec(conn, "TRUNCATE source_map_artifacts RESTART IDENTITY CASCADE");
      exec(conn, "TRUNCATE releases RESTART IDENTITY CASCADE");
    }
  }

  @Test
  void resolvesArtifactByProjectVersionAndPath() throws Exception {
    long releaseId = insertRelease(101L, "1.2.3");
    insertArtifact(
        releaseId, "1/101/" + releaseId + "/dist/app.js.map", "dist/app.js", "a".repeat(64));

    Optional<ArtifactRow> hit = repository.findArtifact(101L, "1.2.3", "dist/app.js");
    assertThat(hit).isPresent();
    assertThat(hit.orElseThrow().r2Key()).isEqualTo("1/101/" + releaseId + "/dist/app.js.map");
    assertThat(hit.orElseThrow().releaseId()).isEqualTo(releaseId);
  }

  @Test
  void wrongProjectIsolatesEvenWhenVersionAndPathMatch() throws Exception {
    long releaseId101 = insertRelease(101L, "1.0.0");
    insertArtifact(releaseId101, "k", "dist/app.js", "a".repeat(64));
    long releaseId102 = insertRelease(102L, "1.0.0");
    insertArtifact(releaseId102, "other", "dist/app.js", "b".repeat(64));

    Optional<ArtifactRow> hit = repository.findArtifact(101L, "1.0.0", "dist/app.js");
    assertThat(hit.orElseThrow().releaseId()).isEqualTo(releaseId101);
  }

  @Test
  void unknownVersionReturnsEmpty() throws Exception {
    long releaseId = insertRelease(101L, "1.2.3");
    insertArtifact(releaseId, "k", "dist/app.js", "a".repeat(64));
    assertThat(repository.findArtifact(101L, "9.9.9", "dist/app.js")).isEmpty();
  }

  @Test
  void unknownPathReturnsEmpty() throws Exception {
    long releaseId = insertRelease(101L, "1.2.3");
    insertArtifact(releaseId, "k", "dist/app.js", "a".repeat(64));
    assertThat(repository.findArtifact(101L, "1.2.3", "missing/file.js")).isEmpty();
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

  private static void seed(DataSource ds) throws Exception {
    try (Connection conn = ds.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (101, 1, 'web', 'Web', 'js')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (102, 1, 'api', 'Api', 'js')");
    }
  }

  private static long insertRelease(long projectId, String version) throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO releases (project_id, version) VALUES (?, ?) RETURNING id")) {
      stmt.setLong(1, projectId);
      stmt.setString(2, version);
      try (var rs = stmt.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static void insertArtifact(long releaseId, String r2Key, String originalPath, String sha)
      throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO source_map_artifacts (release_id, r2_key, original_path, sha256, size_bytes)"
                    + " VALUES (?, ?, ?, ?, 1024)")) {
      stmt.setLong(1, releaseId);
      stmt.setString(2, r2Key);
      stmt.setString(3, originalPath);
      stmt.setString(4, sha);
      stmt.execute();
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
