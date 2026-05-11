package org.arguslog.worker.billing.application;

import java.time.Clock;
import java.util.List;
import org.arguslog.worker.billing.application.port.PaymentDowngradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Daily auto-downgrade of users on any paid plan whose payment grace window has expired (V27+
 * per-user billing). Designed to be a one-line wrapper around the atomic UPDATE so the only state
 * lives in the DB — re-running on the same instant is a safe no-op. Returns affected owner-org ids
 * so callers can audit / alert with the pre-V27 shape.
 */
@Service
public class PaymentDowngradeService {

  private static final Logger log = LoggerFactory.getLogger(PaymentDowngradeService.class);

  private final PaymentDowngradeRepository repository;
  private final Clock clock;

  public PaymentDowngradeService(PaymentDowngradeRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /**
   * Runs one downgrade pass. Returns the list of affected org ids so callers can audit / surface
   * counts in metrics.
   */
  public List<Long> runOnce() {
    List<Long> downgraded = repository.downgradeExpired(clock.instant());
    if (downgraded.isEmpty()) {
      log.debug("Payment downgrade pass found nothing to do");
    } else {
      log.warn(
          "Payment downgrade pass downgraded {} org(s) to FREE: {}", downgraded.size(), downgraded);
    }
    return downgraded;
  }
}
