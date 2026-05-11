package org.arguslog.worker.billing.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.arguslog.worker.billing.application.port.RenewalEmailSender;
import org.arguslog.worker.billing.application.port.RenewalReminderRepository;
import org.arguslog.worker.billing.application.port.RenewalReminderRepository.ReminderCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Daily renewal-reminder pass. For each of T-14 / T-7 / T-1 buckets, queries Pro orgs whose
 * one-time plan expires on that exact day, marks the (org, target_date, kind) as sent (atomic
 * INSERT ON CONFLICT), and only on a successful insert dispatches the Resend email. The dedup write
 * happens FIRST so a re-run of this job — or a sibling worker on Railway — never double-sends the
 * same reminder; the trade-off is that an email transport failure leaves the dedup row in place
 * (we'd rather skip a reminder than spam).
 */
@Service
public class RenewalReminderService {

  private static final Logger log = LoggerFactory.getLogger(RenewalReminderService.class);
  private static final int[] KINDS = {14, 7, 1};

  private final RenewalReminderRepository repository;
  private final RenewalEmailSender mailer;
  private final Clock clock;

  public RenewalReminderService(
      RenewalReminderRepository repository, RenewalEmailSender mailer, Clock clock) {
    this.repository = repository;
    this.mailer = mailer;
    this.clock = clock;
  }

  public int runOnce() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    int sent = 0;
    for (int kind : KINDS) {
      LocalDate target = today.plusDays(kind);
      List<ReminderCandidate> candidates = repository.findCandidates(target, kind);
      for (ReminderCandidate candidate : candidates) {
        if (!repository.markSent(candidate.orgId(), target, kind)) {
          continue; // sibling worker won the race
        }
        boolean delivered =
            mailer.send(
                candidate.ownerEmail(),
                candidate.orgName(),
                candidate.orgSlug(),
                candidate.planExpiresAt(),
                kind);
        if (delivered) sent++;
        else
          log.warn(
              "renewal reminder dispatch failed for org={} kind=T-{} email={}",
              candidate.orgId(),
              kind,
              candidate.ownerEmail());
      }
    }
    if (sent > 0) {
      log.info("renewal reminder pass dispatched {} emails", sent);
    } else {
      log.debug("renewal reminder pass had nothing to do");
    }
    return sent;
  }
}
