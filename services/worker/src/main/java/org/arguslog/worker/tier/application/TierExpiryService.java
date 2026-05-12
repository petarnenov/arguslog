package org.arguslog.worker.tier.application;

import java.time.Clock;
import java.util.List;
import org.arguslog.worker.tier.application.port.TierExpiryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Daily downgrade of users whose admin-granted tier window has elapsed. OSS-conversion replacement
 * for the legacy PaymentDowngradeJob: simpler model (one column to check, no grace), same return
 * shape (affected owner-org ids) so audit / alert downstream stays the same.
 */
@Service
public class TierExpiryService {

  private static final Logger log = LoggerFactory.getLogger(TierExpiryService.class);

  private final TierExpiryRepository repository;
  private final Clock clock;

  public TierExpiryService(TierExpiryRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /** Runs one downgrade pass. Returns affected org ids for callers to log / audit. */
  public List<Long> runOnce() {
    List<Long> downgraded = repository.downgradeExpiredTiers(clock.instant());
    if (downgraded.isEmpty()) {
      log.debug("Tier expiry pass found nothing to do");
    } else {
      log.warn(
          "Tier expiry pass downgraded {} org(s) to regular tier: {}",
          downgraded.size(),
          downgraded);
    }
    return downgraded;
  }
}
