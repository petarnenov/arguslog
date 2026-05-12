package org.arguslog.worker.tier.application.port;

import java.time.Instant;
import java.util.List;

/**
 * One-step downgrade of users whose admin-granted tier has expired. Returns the affected owner-org
 * ids so callers can audit / alert with the same shape the legacy billing-era
 * PaymentDowngradeRepository used.
 */
public interface TierExpiryRepository {

  /**
   * For every user with {@code tier <> 'regular'} and {@code tier_expires_at < now}, drop them back
   * to regular and clear the grant metadata. Returns the org ids resolved from the downgraded
   * users' owned orgs. Idempotent — running twice on the same instant updates nothing the second
   * time.
   */
  List<Long> downgradeExpiredTiers(Instant now);
}
