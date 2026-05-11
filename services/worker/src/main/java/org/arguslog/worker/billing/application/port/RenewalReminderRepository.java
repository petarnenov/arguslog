package org.arguslog.worker.billing.application.port;

import java.time.LocalDate;
import java.util.List;

/**
 * Per-day candidate query for "Pro orgs whose plan expires {@code daysAhead} days from now and
 * haven't already been emailed for that target date and kind". Owner-of-record is the {@code owner}
 * role on the org; we only email the primary owner to avoid spamming entire teams.
 */
public interface RenewalReminderRepository {

  List<ReminderCandidate> findCandidates(LocalDate targetDate, int kind);

  /**
   * Records the (org, target_date, kind) so a re-run today won't re-send. Returns {@code true} iff
   * the row was newly inserted; {@code false} if a sibling worker beat us to it. Senders should
   * only invoke email send when this returns {@code true}.
   */
  boolean markSent(long orgId, LocalDate targetDate, int kind);

  record ReminderCandidate(
      long orgId, String orgSlug, String orgName, String ownerEmail, LocalDate planExpiresAt) {}
}
