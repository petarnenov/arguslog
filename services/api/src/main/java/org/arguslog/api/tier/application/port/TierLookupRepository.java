package org.arguslog.api.tier.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.billing.PlanTier;

/**
 * Read-side port for the OSS-era tier model. Replaces the old per-org and per-user billing
 * repositories with a single port focused on "what tier does this principal currently hold + when
 * does any grant expire". The hosted instance still has the concept of admin-granted upgrades — V30
 * renamed the {@code bonus_*} columns into {@code tier_*}, and elevation is permanent unless {@code
 * tier_expires_at} is set.
 *
 * <p>Quota checks (project / member / org cap, retention floor) come straight off the returned
 * {@link PlanTier}. Anything related to billing intervals, renewal dates, or payment grace is gone
 * — V30 dropped those columns and the OSS distribution has no checkout flow.
 */
public interface TierLookupRepository {

  /**
   * Highest tier among the owners of {@code orgId}. Empty when the org has no owners (rare orphan);
   * callers fall back to {@link PlanTier#REGULAR} explicitly so the most-restrictive default
   * applies.
   */
  Optional<PlanTier> findTier(long orgId);

  /**
   * Returns the user's current tier (from {@code users.tier}). Empty when the user row is missing —
   * first-time signups should hit this only after the JWT-sync interceptor has created the row, so
   * empty here implies a genuine bug.
   */
  Optional<PlanTier> findTierForUser(UUID userId);

  /**
   * Active grant metadata for {@code userId} when an admin has elevated their tier with a {@code
   * tier_expires_at} in the future. Empty when no active grant exists (user is on their default
   * tier permanently). The {@code tier} column itself is the source of truth for caps; this read is
   * dashboard-banner metadata only.
   */
  Optional<TierGrantSnapshot> findActiveTierGrant(UUID userId);

  /**
   * Lightweight grant projection — when an admin granted the user a higher tier with an expiry
   * date, the dashboard surfaces who/why/until-when.
   */
  record TierGrantSnapshot(Instant expiresAt, String reason, String grantedByEmail) {}
}
