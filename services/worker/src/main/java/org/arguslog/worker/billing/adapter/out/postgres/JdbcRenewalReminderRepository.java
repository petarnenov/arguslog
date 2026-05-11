package org.arguslog.worker.billing.adapter.out.postgres;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.arguslog.worker.billing.application.port.RenewalReminderRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Per-user renewal-reminder lookup (V27+). Billing identity lives on the owner-user row, so plan +
 * {@code plan_renews_at} are read from {@code users}; the email recipient is that same user. The
 * dedup table {@code renewal_reminders_sent} stays keyed by {@code org_id} since the email body is
 * org-scoped ("Org &lt;name&gt; is expiring") — a user who owns multiple orgs gets one email per
 * org, mirroring how their dashboard surfaces each org's expiry independently.
 *
 * <p>The {@code plan != 'free'} predicate catches every paid tier (STARTER / PRO / BUSINESS /
 * ENTERPRISE). The pre-V23 {@code plan = 'pro'} predicate was a holdover that silently skipped
 * starter/business owners from the T-14/-7/-1 reminder cycle.
 */
@Repository
public class JdbcRenewalReminderRepository implements RenewalReminderRepository {

  private static final String FIND_CANDIDATES_SQL =
      """
      SELECT o.id, o.slug, o.name, u.email, u.plan_renews_at::date AS expires_on
      FROM organizations o
      JOIN org_members m ON m.org_id = o.id AND m.role = 'owner'::org_role
      JOIN users u ON u.id = m.user_id
      WHERE u.plan != 'free'::org_plan
        AND u.plan_renews_at IS NOT NULL
        AND u.plan_renews_at::date = ?
        AND NOT EXISTS (
          SELECT 1 FROM renewal_reminders_sent rs
          WHERE rs.org_id = o.id AND rs.target_date = ? AND rs.kind = ?
        )
      """;

  private static final String MARK_SENT_SQL =
      "INSERT INTO renewal_reminders_sent (org_id, target_date, kind) VALUES (?, ?, ?)"
          + " ON CONFLICT (org_id, target_date, kind) DO NOTHING";

  private final JdbcTemplate jdbc;

  public JdbcRenewalReminderRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public List<ReminderCandidate> findCandidates(LocalDate targetDate, int kind) {
    return jdbc.query(
        FIND_CANDIDATES_SQL,
        (PreparedStatement ps) -> {
          ps.setDate(1, Date.valueOf(targetDate));
          ps.setDate(2, Date.valueOf(targetDate));
          ps.setInt(3, kind);
        },
        rowMapper());
  }

  @Override
  public boolean markSent(long orgId, LocalDate targetDate, int kind) {
    int rows = jdbc.update(MARK_SENT_SQL, orgId, Date.valueOf(targetDate), kind);
    return rows == 1;
  }

  private static RowMapper<ReminderCandidate> rowMapper() {
    return (ResultSet rs, int rowNum) ->
        new ReminderCandidate(
            rs.getLong("id"),
            rs.getString("slug"),
            rs.getString("name"),
            rs.getString("email"),
            rs.getDate("expires_on").toLocalDate());
  }
}
