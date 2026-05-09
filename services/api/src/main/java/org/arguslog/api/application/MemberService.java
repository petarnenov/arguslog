package org.arguslog.api.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.MembershipWriteRepository;
import org.arguslog.api.application.port.UserRepository;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.billing.domain.PlanTier;
import org.arguslog.api.domain.Member;
import org.arguslog.api.email.InviteEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService implements MemberUseCase {

  private static final Logger log = LoggerFactory.getLogger(MemberService.class);
  private static final Set<String> VALID_ROLES = Set.of("owner", "admin", "member");
  private static final int MAX_EMAIL_LENGTH = 254;

  private final MembershipRepository memberships;
  private final MembershipWriteRepository membershipWrites;
  private final UserRepository users;
  private final InviteEmailSender inviteEmails;
  private final OrgPlanRepository plans;

  public MemberService(
      MembershipRepository memberships,
      MembershipWriteRepository membershipWrites,
      UserRepository users,
      InviteEmailSender inviteEmails,
      OrgPlanRepository plans) {
    this.memberships = memberships;
    this.membershipWrites = membershipWrites;
    this.users = users;
    this.inviteEmails = inviteEmails;
    this.plans = plans;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Member> list(long orgId) {
    return memberships.listMembersOf(orgId);
  }

  @Override
  @Transactional
  public Member invite(UUID actorId, long orgId, String rawEmail, String rawRole) {
    requireOwner(actorId, orgId);
    String email = requireEmail(rawEmail);
    String role = requireRole(rawRole);
    requireMemberCapAvailable(orgId);

    UUID userId = users.findIdByEmail(email).orElseGet(() -> users.createPlaceholder(email));

    boolean inserted = membershipWrites.addMember(orgId, userId, role);
    if (!inserted) {
      throw new DuplicateMemberException(email + " is already a member of this organization.");
    }

    inviteEmails.send(email, orgId);
    return memberships.listMembersOf(orgId).stream()
        .filter(m -> m.userId().equals(userId))
        .findFirst()
        // Should never happen — we just inserted in the same tx — but keep the type system honest.
        .orElseThrow(
            () -> new IllegalStateException("just-inserted member missing from listMembersOf"));
  }

  private void requireMemberCapAvailable(long orgId) {
    PlanTier tier = plans.findPlan(orgId).orElse(PlanTier.FREE);
    int cap = tier.memberCap();
    if (cap == Integer.MAX_VALUE) return;
    int existing = memberships.listMembersOf(orgId).size();
    if (existing >= cap) {
      throw new MemberCapExceededException(
          "Your "
              + tier.dbValue()
              + " plan is limited to "
              + cap
              + " member"
              + (cap == 1 ? "" : "s")
              + ". Upgrade or remove an existing member to add another.");
    }
  }

  @Override
  @Transactional
  public Member changeRole(UUID actorId, long orgId, UUID targetUserId, String rawRole) {
    requireOwner(actorId, orgId);
    String role = requireRole(rawRole);

    String currentRole =
        memberships
            .userRoleInOrg(targetUserId, orgId)
            .orElseThrow(() -> new MemberNotFoundException("User is not a member of this org."));
    if (currentRole.equals(role)) {
      // No-op; return current state without writing. Avoids a redundant audit/log entry.
      return findMember(orgId, targetUserId);
    }

    if ("owner".equals(currentRole) && !"owner".equals(role)) {
      guardLastOwner(orgId);
    }

    boolean updated = membershipWrites.updateRole(orgId, targetUserId, role);
    if (!updated) {
      // Race: somebody removed the row between our role read and the UPDATE. Surface as not-found
      // rather than swallowing.
      throw new MemberNotFoundException("User is not a member of this org.");
    }
    return findMember(orgId, targetUserId);
  }

  @Override
  @Transactional
  public void remove(UUID actorId, long orgId, UUID targetUserId) {
    String actorRole =
        memberships
            .userRoleInOrg(actorId, orgId)
            .orElseThrow(
                () -> new MemberAccessDeniedException("You are not a member of this org."));

    boolean isSelfRemoval = actorId.equals(targetUserId);
    if (!isSelfRemoval && !"owner".equals(actorRole)) {
      throw new MemberAccessDeniedException("Only owners can remove other members.");
    }

    Optional<String> targetRole = memberships.userRoleInOrg(targetUserId, orgId);
    if (targetRole.isEmpty()) {
      throw new MemberNotFoundException("User is not a member of this org.");
    }
    if ("owner".equals(targetRole.get())) {
      guardLastOwner(orgId);
    }

    if (!membershipWrites.removeMember(orgId, targetUserId)) {
      // Concurrent removal — already gone is fine for the caller's intent.
      log.debug("removeMember no-op: org={} user={} already removed", orgId, targetUserId);
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private void requireOwner(UUID actorId, long orgId) {
    String role =
        memberships
            .userRoleInOrg(actorId, orgId)
            .orElseThrow(
                () -> new MemberAccessDeniedException("You are not a member of this org."));
    if (!"owner".equals(role)) {
      throw new MemberAccessDeniedException("Only org owners can manage members.");
    }
  }

  private void guardLastOwner(long orgId) {
    if (memberships.countOwnersOf(orgId) <= 1) {
      throw new LastOwnerException(
          "Cannot remove or demote the last owner. Promote another member first.");
    }
  }

  private Member findMember(long orgId, UUID userId) {
    return memberships.listMembersOf(orgId).stream()
        .filter(m -> m.userId().equals(userId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("member vanished mid-transaction"));
  }

  private static String requireEmail(String raw) {
    if (raw == null) {
      throw new InvalidMemberException("email is required");
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      throw new InvalidMemberException("email is required");
    }
    if (trimmed.length() > MAX_EMAIL_LENGTH) {
      throw new InvalidMemberException("email is too long");
    }
    // Minimal shape check — DB CITEXT will store anything; we just want to reject obvious garbage
    // before pre-creating a placeholder. We do NOT do RFC-5322 — Keycloak validates real syntax at
    // signup. This catches "no @ sign" and similar 95% wins.
    int at = trimmed.indexOf('@');
    if (at <= 0 || at == trimmed.length() - 1 || trimmed.indexOf('@', at + 1) >= 0) {
      throw new InvalidMemberException("email is not a valid address");
    }
    return trimmed;
  }

  private static String requireRole(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new InvalidMemberException("role is required");
    }
    String role = raw.trim().toLowerCase();
    if (!VALID_ROLES.contains(role)) {
      throw new InvalidMemberException("role must be one of: " + VALID_ROLES);
    }
    return role;
  }
}
