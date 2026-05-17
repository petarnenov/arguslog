package org.arguslog.api.releases.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import javax.sql.DataSource;
import org.arguslog.api.releases.application.port.SourceMapArtifactRepository;
import org.arguslog.api.releases.domain.SourceMapArtifact;
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
class JdbcSourceMapArtifactRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:latest-pg16")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource dataSource;
  private static SourceMapArtifactRepository repository;
  private static org.arguslog.api.releases.application.port.SourceMapArtifactWriteRepository writes;

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
    JdbcSourceMapArtifactRepository raw = new JdbcSourceMapArtifactRepository(dataSource);
    repository =
        new SourceMapArtifactRepository() {
          @Override
          public List<SourceMapArtifact> listForRelease(long releaseId) {
            return tx.execute(s -> raw.listForRelease(releaseId));
          }

          @Override
          public java.util.Optional<SourceMapArtifact> findUnderRelease(
              long releaseId, long artifactId) {
            return tx.execute(s -> raw.findUnderRelease(releaseId, artifactId));
          }
        };
    writes =
        new org.arguslog.api.releases.application.port.SourceMapArtifactWriteRepository() {
          @Override
          public SourceMapArtifact upsert(
              long releaseId, String r2Key, String originalPath, String sha256, long sizeBytes) {
            return tx.execute(s -> raw.upsert(releaseId, r2Key, originalPath, sha256, sizeBytes));
          }

          @Override
          public boolean delete(long releaseId, long artifactId) {
            return Boolean.TRUE.equals(tx.execute(s -> raw.delete(releaseId, artifactId)));
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
      exec(conn, "TRUNCATE source_map_artifacts RESTART IDENTITY CASCADE");
    }
  }

  @AfterEach
  void clear() {
    OrgContext.clear();
  }

  @Test
  void insertReturnsPersistedRow() {
    SourceMapArtifact out =
        writes.upsert(777L, "1/101/777/dist/app.js.map", "dist/app.js", "a".repeat(64), 1234L);
    assertThat(out.id()).isPositive();
    assertThat(out.releaseId()).isEqualTo(777L);
    assertThat(out.r2Key()).isEqualTo("1/101/777/dist/app.js.map");
    assertThat(out.originalPath()).isEqualTo("dist/app.js");
    assertThat(out.sha256()).isEqualTo("a".repeat(64));
    assertThat(out.sizeBytes()).isEqualTo(1234L);
    assertThat(out.createdAt()).isNotNull();
  }

  @Test
  void upsertReplacesExistingRowKeepingId() {
    SourceMapArtifact first =
        writes.upsert(777L, "old/key.map", "dist/app.js", "a".repeat(64), 100L);
    SourceMapArtifact second =
        writes.upsert(777L, "new/key.map", "dist/app.js", "b".repeat(64), 200L);

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.r2Key()).isEqualTo("new/key.map");
    assertThat(second.sha256()).isEqualTo("b".repeat(64));
    assertThat(second.sizeBytes()).isEqualTo(200L);

    List<SourceMapArtifact> rows = repository.listForRelease(777L);
    assertThat(rows).hasSize(1).first().isEqualTo(second);
  }

  @Test
  void differentPathsCoexistWithinSameRelease() {
    writes.upsert(777L, "k1.map", "dist/app.js", "a".repeat(64), 100L);
    writes.upsert(777L, "k2.map", "dist/vendor.js", "b".repeat(64), 200L);

    List<SourceMapArtifact> rows = repository.listForRelease(777L);
    // Sorted by original_path ASC.
    assertThat(rows)
        .extracting(SourceMapArtifact::originalPath)
        .containsExactly("dist/app.js", "dist/vendor.js");
  }

  @Test
  void findUnderReleaseReturnsTheArtifact() {
    SourceMapArtifact created = writes.upsert(777L, "k.map", "dist/app.js", "a".repeat(64), 100L);

    assertThat(repository.findUnderRelease(777L, created.id())).contains(created);
    assertThat(repository.findUnderRelease(777L, created.id() + 9999)).isEmpty();
    // Wrong release id returns empty even when the artifact exists in another release.
    assertThat(repository.findUnderRelease(778L, created.id())).isEmpty();
  }

  @Test
  void deleteReturnsTrueWhenRowExists() {
    SourceMapArtifact created = writes.upsert(777L, "k.map", "dist/app.js", "a".repeat(64), 100L);

    assertThat(writes.delete(777L, created.id())).isTrue();
    assertThat(repository.findUnderRelease(777L, created.id())).isEmpty();
    // Second delete is a no-op — surfaces as 404 from the service layer.
    assertThat(writes.delete(777L, created.id())).isFalse();
  }

  @Test
  void deleteIsReleaseScoped() {
    SourceMapArtifact created = writes.upsert(777L, "k.map", "dist/app.js", "a".repeat(64), 100L);

    // Wrong release id leaves the row in place.
    assertThat(writes.delete(778L, created.id())).isFalse();
    assertThat(repository.findUnderRelease(777L, created.id())).isPresent();
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static void seed(DataSource ds) throws Exception {
    try (Connection conn = ds.getConnection()) {
      exec(conn, "INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme')");
      exec(
          conn,
          "INSERT INTO projects (id, org_id, slug, name, platform) VALUES (101, 1, 'web', 'Web', 'javascript')");
      exec(conn, "INSERT INTO releases (id, project_id, version) VALUES (777, 101, '1.2.3')");
    }
  }

  private static void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
