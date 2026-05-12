package org.arguslog.api.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Locks in V26's per-user billing backfill semantics. Migrates to V25 first, seeds three
 * representative shapes (multi-org owner mixing tiers, single-org owner, ownerless user), then runs
 * V26 and asserts the user-level rows have the expected billing identity:
 *
 * <ul>
 *   <li>An owner of (FREE + PRO) gets PRO + the PRO org's renew/billing_interval/stripe.
 *   <li>An owner of (FREE) gets FREE with default monthly cadence.
 *   <li>A user who owns nothing stays on the column defaults (free / monthly / nulls).
 * </ul>
 *
 * Drift here means the backfill silently misassigns paid tiers — paying customers would either lose
 * their plan on migrate or non-paying ones would get a free upgrade. Worth catching in CI.
 */
@Testcontainers
class V26UserBillingBackfillTest {

  private static final DockerImageName TIMESCALE_IMAGE =
      DockerImageName.parse("timescale/timescaledb:latest-pg16")
          .asCompatibleSubstituteFor("postgres");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TIMESCALE_IMAGE)
          .withDatabaseName("arguslog")
          .withUsername("arguslog")
          .withPassword("arguslog");

  @Test
  void backfillsHighestTierFromOwnedOrgsAndCopiesItsBillingIdentity() throws Exception {
    // 1. Migrate to V25 — the last revision before V26 mirrors columns onto users.
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .target("25")
        .load()
        .migrate();

    UUID userMulti = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID userSingle = UUID.fromString("22222222-2222-2222-2222-222222222222");
    UUID userNone = UUID.fromString("33333333-3333-3333-3333-333333333333");

    try (Connection conn =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      conn.setAutoCommit(true);

      insertUser(conn, userMulti, "multi@example.com");
      insertUser(conn, userSingle, "single@example.com");
      insertUser(conn, userNone, "none@example.com");

      // userMulti owns one FREE and one PRO org. The PRO org has the latest renew + stripe id
      // and is the row whose billing identity should win the backfill.
      long freeOrg = insertOrg(conn, "free-org", "Free Org", "free", null, "monthly", null);
      long proOrg =
          insertOrg(
              conn, "pro-org", "Pro Org", "pro", "2026-06-30 12:00:00+00", "annual", "cus_pro_abc");
      insertMembership(conn, freeOrg, userMulti, "owner");
      insertMembership(conn, proOrg, userMulti, "owner");

      long lonelyFreeOrg =
          insertOrg(conn, "single-free", "Solo Free", "free", null, "monthly", null);
      insertMembership(conn, lonelyFreeOrg, userSingle, "owner");

      // userNone exists but owns no org — verifies the LEFT-side default behaviour.
    }

    // 2. Run V26.
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    try (Connection conn =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      UserBilling multi = readUserBilling(conn, userMulti);
      assertThat(multi.plan).isEqualTo("pro");
      assertThat(multi.billingInterval).isEqualTo("annual");
      assertThat(multi.stripeCustomerId).isEqualTo("cus_pro_abc");
      assertThat(multi.planRenewsAt).isNotNull();

      UserBilling single = readUserBilling(conn, userSingle);
      assertThat(single.plan).isEqualTo("free");
      assertThat(single.billingInterval).isEqualTo("monthly");
      assertThat(single.stripeCustomerId).isNull();

      UserBilling none = readUserBilling(conn, userNone);
      assertThat(none.plan).isEqualTo("free");
      assertThat(none.billingInterval).isEqualTo("monthly");
      assertThat(none.stripeCustomerId).isNull();
    }
  }

  private static void insertUser(Connection conn, UUID id, String email) throws Exception {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO users (id, email, display_name, last_seen_at) VALUES (?, ?, ?, NOW())")) {
      ps.setObject(1, id);
      ps.setString(2, email);
      ps.setString(3, email);
      ps.executeUpdate();
    }
  }

  private static long insertOrg(
      Connection conn,
      String slug,
      String name,
      String plan,
      String planRenewsAt,
      String billingInterval,
      String stripeCustomerId)
      throws Exception {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO organizations (slug, name, plan, plan_renews_at, billing_interval, stripe_customer_id)
            VALUES (?, ?, ?::org_plan, ?::timestamptz, ?::billing_interval_t, ?)
            RETURNING id
            """)) {
      ps.setString(1, slug);
      ps.setString(2, name);
      ps.setString(3, plan);
      ps.setString(4, planRenewsAt);
      ps.setString(5, billingInterval);
      ps.setString(6, stripeCustomerId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static void insertMembership(Connection conn, long orgId, UUID userId, String role)
      throws Exception {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO org_members (org_id, user_id, role) VALUES (?, ?, ?::org_role)")) {
      ps.setLong(1, orgId);
      ps.setObject(2, userId);
      ps.setString(3, role);
      ps.executeUpdate();
    }
  }

  private static UserBilling readUserBilling(Connection conn, UUID id) throws Exception {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            SELECT plan::text, plan_renews_at, billing_interval::text, stripe_customer_id
              FROM users WHERE id = ?
            """)) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        UserBilling out = new UserBilling();
        out.plan = rs.getString(1);
        out.planRenewsAt = rs.getTimestamp(2);
        out.billingInterval = rs.getString(3);
        out.stripeCustomerId = rs.getString(4);
        return out;
      }
    }
  }

  private static final class UserBilling {
    String plan;
    java.sql.Timestamp planRenewsAt;
    String billingInterval;
    String stripeCustomerId;
  }
}
