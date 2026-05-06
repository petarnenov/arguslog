package org.arguslog.worker.retention.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.arguslog.worker.retention.application.port.OrgRetentionRepository;
import org.arguslog.worker.retention.application.port.RetentionPurgeRepository;
import org.arguslog.worker.retention.domain.OrgRetention;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates per-org event retention. Reads the orgs whose effective retention is below the
 * TimescaleDB chunk-policy floor (365d), then for each org batches DELETE calls until none remain.
 *
 * <p>Dry-run mode (default true on first deploy) replaces the DELETE with a count + log so the
 * operator can sanity-check scope before flipping the switch.
 */
@Service
public class RetentionPurgeService {

  private static final Logger log = LoggerFactory.getLogger(RetentionPurgeService.class);

  /** Matches the api migration {@code V10__events_retention_policy.sql}. */
  private static final Duration CHUNK_POLICY_FLOOR = Duration.ofDays(365);

  private final OrgRetentionRepository orgs;
  private final RetentionPurgeRepository purger;
  private final Clock clock;
  private final boolean dryRun;
  private final int batchSize;

  public RetentionPurgeService(
      OrgRetentionRepository orgs,
      RetentionPurgeRepository purger,
      Clock clock,
      @Value("${arguslog.retention.dry-run:true}") boolean dryRun,
      @Value("${arguslog.retention.batch-size:10000}") int batchSize) {
    this.orgs = orgs;
    this.purger = purger;
    this.clock = clock;
    this.dryRun = dryRun;
    this.batchSize = batchSize;
  }

  /**
   * Runs one retention pass across all orgs below the floor. Safe to call repeatedly — the cutoff
   * is recomputed each time from {@link Clock#instant()}, and the batch loop terminates when no
   * eligible rows remain.
   *
   * @return total rows deleted across all orgs (or {@code -1} if dry-run was on)
   */
  public long runOnce() {
    List<OrgRetention> targets = orgs.orgsBelowFloor(CHUNK_POLICY_FLOOR);
    Instant now = clock.instant();
    log.info(
        "Retention pass started — orgs below {}-day floor: {}, dryRun={}",
        CHUNK_POLICY_FLOOR.toDays(),
        targets.size(),
        dryRun);

    if (dryRun) {
      long wouldDelete = 0L;
      for (OrgRetention org : targets) {
        Instant cutoff = now.minus(org.effectiveRetention());
        long n = purger.countEligible(org.orgId(), cutoff);
        wouldDelete += n;
        log.info(
            "[dry-run] org={} retention={}d cutoff={} would_delete={}",
            org.orgId(),
            org.effectiveRetention().toDays(),
            cutoff,
            n);
      }
      log.info("[dry-run] retention pass complete — would_delete_total={}", wouldDelete);
      return -1L;
    }

    long totalDeleted = 0L;
    for (OrgRetention org : targets) {
      Instant cutoff = now.minus(org.effectiveRetention());
      long deletedForOrg = purgeOrg(org.orgId(), cutoff);
      totalDeleted += deletedForOrg;
    }
    log.info("Retention pass complete — deleted_total={}", totalDeleted);
    return totalDeleted;
  }

  private long purgeOrg(long orgId, Instant cutoff) {
    long deleted = 0L;
    while (true) {
      int n = purger.purgeBatch(orgId, cutoff, batchSize);
      deleted += n;
      // A short batch means the table is empty for this cutoff — bail before issuing a no-op
      // DELETE and racing with concurrent writes that arrive after `now` was sampled.
      if (n < batchSize) break;
    }
    if (deleted > 0) log.info("Purged org={} cutoff={} rows={}", orgId, cutoff, deleted);
    return deleted;
  }
}
