package org.arguslog.api.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.domain.Member;

/** Read-side port for org_members. */
public interface MembershipRepository {
  boolean userIsMemberOfOrg(UUID userId, long orgId);

  /** Role of {@code userId} in {@code orgId}, or empty if not a member. */
  Optional<String> userRoleInOrg(UUID userId, long orgId);

  /**
   * Members of {@code orgId} joined with their {@code users} row, ordered by added_at ascending so
   * the founder appears first. Includes role.
   */
  List<Member> listMembersOf(long orgId);

  /**
   * How many members of {@code orgId} hold the {@code owner} role. Used to block last-owner exits.
   */
  int countOwnersOf(long orgId);

  /**
   * The user's "primary owned org" — same picker rule that resolves billing-relevant joins
   * everywhere else: highest current tier wins, ties broken by earliest membership. Returns
   * empty for users who own no org yet (first-time signup or member-only access). Per-user
   * billing endpoints use this to delegate to the org-scoped Stripe/NOWPayments flow without
   * the frontend having to pick an org for the user.
   */
  Optional<Long> findPrimaryOwnedOrg(UUID userId);
}
