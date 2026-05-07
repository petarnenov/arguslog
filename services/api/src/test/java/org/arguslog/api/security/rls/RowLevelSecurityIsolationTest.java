package org.arguslog.api.security.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Locks the row-level security policies in {@code V1__initial_schema.sql} as a real isolation
 * boundary, not just a soft hint.
 *
 * <p>Why a separate test class: every other Testcontainers test in the api module connects as the
 * Postgres superuser ({@code postgres}), which carries the implicit BYPASSRLS attribute and
 * therefore never triggers the RLS policies — useful for setup, useless for proving the policies
 * actually fire. This test deliberately splits the connections in two:
 *
 * <ol>
 *   <li><b>Owner connection</b> (superuser, bypasses RLS) — runs Flyway, seeds two orgs and one
 *       project per org. Required to plant cross-org data so RLS has something to filter.
 *   <li><b>App connection</b> (non-owner role with NOBYPASSRLS) — exercises every policy as the app
 *       would in production. {@code arguslog.org_id} is set per "request", and the test asserts
 *       that reads return only the requested org's rows and that inserts honor the {@code WITH
 *       CHECK} clauses.
 * </ol>
 *
 * <p>The owner-vs-app split mirrors what Railway's managed Postgres requires anyway — the {@code
 * postgres} role provisions the schema, app connections use a separate role with the minimum needed
 * grants and NOBYPASSRLS.
 */
@Testcontainers
class RowLevelSecurityIsolationTest {

  private static final DockerImageName TIMESCALE =
      DockerImageName.parse("timescale/timescaledb:latest-pg16")
          .asCompatibleSubstituteFor("postgres");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TIMESCALE)
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  private static HikariDataSource ownerDs;
  private static HikariDataSource appDs;

  @BeforeAll
  static void boot() throws Exception {
    ownerDs = pool(POSTGRES.getUsername(), POSTGRES.getPassword());

    Flyway.configure().dataSource(ownerDs).locations(resolveMigrations()).load().migrate();

    // Seed two orgs + one project each so the RLS-checked reads have something to filter.
    try (Connection conn = ownerDs.getConnection();
        Statement st = conn.createStatement()) {
      st.execute("INSERT INTO organizations (id, slug, name) VALUES (1, 'acme', 'Acme')");
      st.execute("INSERT INTO organizations (id, slug, name) VALUES (2, 'other', 'Other')");
      st.execute(
          "INSERT INTO projects (id, org_id, slug, name, platform)"
              + " VALUES (101, 1, 'web', 'Web', 'javascript')");
      st.execute(
          "INSERT INTO projects (id, org_id, slug, name, platform)"
              + " VALUES (201, 2, 'api', 'API', 'java')");
    }

    // Create a non-owner role that the app pretends to be. NOBYPASSRLS is the critical bit —
    // any SELECT/INSERT/UPDATE/DELETE this role issues must pass the policies.
    try (Connection conn = ownerDs.getConnection();
        Statement st = conn.createStatement()) {
      st.execute("CREATE ROLE app_role LOGIN PASSWORD 'app_pwd' NOBYPASSRLS NOSUPERUSER");
      st.execute("GRANT USAGE ON SCHEMA public TO app_role");
      st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_role");
      st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_role");
    }

    appDs = pool("app_role", "app_pwd");
  }

  @AfterAll
  static void stop() {
    if (appDs != null) appDs.close();
    if (ownerDs != null) ownerDs.close();
  }

  // ── reads ───────────────────────────────────────────────────────────────

  @Test
  void appRoleSeesOnlyItsOrgsProjects() throws Exception {
    try (Connection conn = appDs.getConnection()) {
      setOrgContext(conn, 1L);
      List<Long> ids = projectIds(conn);
      assertThat(ids).containsExactly(101L);

      setOrgContext(conn, 2L);
      ids = projectIds(conn);
      assertThat(ids).containsExactly(201L);
    }
  }

  @Test
  void appRoleSeesNoProjectsWhenOrgContextIsUnset() throws Exception {
    // RLS USING(...) returns false when current_setting fails — no rows leak.
    try (Connection conn = appDs.getConnection()) {
      assertThat(projectIds(conn)).isEmpty();
    }
  }

  @Test
  void appRoleSeesNoProjectsWhenOrgContextPointsAtOrgWithNone() throws Exception {
    try (Connection conn = appDs.getConnection()) {
      setOrgContext(conn, 999L); // unknown org
      assertThat(projectIds(conn)).isEmpty();
    }
  }

  // ── writes ──────────────────────────────────────────────────────────────

  @Test
  void appRoleCannotInsertProjectIntoAnotherOrg() throws Exception {
    try (Connection conn = appDs.getConnection()) {
      setOrgContext(conn, 1L);
      try (PreparedStatement ps =
          conn.prepareStatement(
              "INSERT INTO projects (org_id, slug, name, platform)"
                  + " VALUES (?, 'evil', 'Evil', 'go')")) {
        ps.setLong(1, 2L); // pretending to be org 1, writing into org 2
        assertThatThrownBy(ps::execute)
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("row-level security");
      }
    }
  }

  @Test
  void appRoleCanInsertProjectIntoItsOwnOrg() throws Exception {
    try (Connection conn = appDs.getConnection()) {
      setOrgContext(conn, 1L);
      try (PreparedStatement ps =
          conn.prepareStatement(
              "INSERT INTO projects (org_id, slug, name, platform)"
                  + " VALUES (?, 'mobile', 'Mobile', 'react-native') RETURNING id")) {
        ps.setLong(1, 1L);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          assertThat(rs.getLong(1)).isPositive();
        }
      }
    }
  }

  // ── owner sanity ────────────────────────────────────────────────────────

  @Test
  void ownerRoleAlwaysSeesEverythingBecauseBypassRlsIsImplicitForSuperuser() throws Exception {
    // This is documentation-via-test: it's WHY we need the app role for real RLS coverage.
    try (Connection conn = ownerDs.getConnection()) {
      assertThat(projectIds(conn)).contains(101L, 201L);
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static HikariDataSource pool(String user, String password) {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
    cfg.setUsername(user);
    cfg.setPassword(password);
    cfg.setMaximumPoolSize(2);
    return new HikariDataSource(cfg);
  }

  private static void setOrgContext(Connection conn, long orgId) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute("SET arguslog.org_id = '" + orgId + "'");
    }
  }

  private static List<Long> projectIds(Connection conn) throws SQLException {
    List<Long> out = new java.util.ArrayList<>();
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT id FROM projects ORDER BY id")) {
      while (rs.next()) out.add(rs.getLong(1));
    }
    return out;
  }

  private static String resolveMigrations() {
    List<Path> candidates = List.of(Path.of("src/main/resources/db/migration"));
    return candidates.stream()
        .map(Path::toAbsolutePath)
        .filter(Files::isDirectory)
        .findFirst()
        .map(p -> "filesystem:" + p)
        .orElseThrow(() -> new IllegalStateException("Cannot locate api migrations"));
  }
}
