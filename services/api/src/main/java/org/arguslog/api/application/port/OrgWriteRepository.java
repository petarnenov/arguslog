package org.arguslog.api.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.domain.Org;

/** Write-side port for organizations + the membership row created alongside them. */
public interface OrgWriteRepository {

  /**
   * Insert a new org with a unique slug derived from {@code baseSlug}. The slug is appended with
   * {@code -2}, {@code -3}, … on collision until a free one is found. {@code planDbValue} is the
   * lower-case wire value from {@link org.arguslog.billing.PlanTier#dbValue()} —
   * pass {@code "free"} for first-time creators, or the creator's highest-active tier so new orgs
   * inherit existing paid coverage (GH #38). The renewal/billing identity is NOT inherited; the
   * new org starts a fresh cycle.
   */
  Org create(String baseSlug, String name, String planDbValue);

  /**
   * Adds {@code userId} as the given role to {@code orgId}. Idempotent on the (org_id, user_id) PK.
   */
  void addMember(long orgId, UUID userId, String role);

  /** Returns every org {@code userId} is a member of, ordered by org slug ascending. */
  List<Org> listForUser(UUID userId);

  /**
   * Returns the count of orgs where {@code userId} holds the {@code owner} role. Used by the org
   * cap quota check — admins / members of other people's orgs are not included.
   */
  int countOwnedBy(UUID userId);

  /** Look up a single org by id. Membership is NOT checked here — callers must guard. */
  Optional<Org> findById(long orgId);

  /**
   * Hard-deletes an org. {@code ON DELETE CASCADE} on every dependent FK propagates the removal to
   * memberships, projects (and their issues/events/keys/etc), alert rules and destinations,
   * releases, and source-map artifacts. Returns {@code false} if the org did not exist.
   */
  boolean delete(long orgId);
}
