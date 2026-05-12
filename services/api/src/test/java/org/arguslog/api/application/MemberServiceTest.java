package org.arguslog.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.application.MemberUseCase.DuplicateMemberException;
import org.arguslog.api.application.MemberUseCase.InvalidMemberException;
import org.arguslog.api.application.MemberUseCase.LastOwnerException;
import org.arguslog.api.application.MemberUseCase.MemberAccessDeniedException;
import org.arguslog.api.application.MemberUseCase.MemberNotFoundException;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.MembershipWriteRepository;
import org.arguslog.api.application.port.UserRepository;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.domain.Member;
import org.arguslog.api.email.InviteEmailSender;
import org.arguslog.billing.PlanTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

  @Mock MembershipRepository memberships;
  @Mock MembershipWriteRepository membershipWrites;
  @Mock UserRepository users;
  @Mock InviteEmailSender inviteEmails;
  @Mock OrgPlanRepository plans;

  MemberService service;

  static final long ORG = 1L;
  static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID INVITEE = UUID.fromString("22222222-2222-2222-2222-222222222222");
  static final UUID OTHER_OWNER = UUID.fromString("33333333-3333-3333-3333-333333333333");
  static final Instant T = Instant.parse("2026-05-08T00:00:00Z");

  @BeforeEach
  void setUp() {
    service = new MemberService(memberships, membershipWrites, users, inviteEmails, plans);
    org.mockito.Mockito.lenient()
        .when(plans.findPlan(anyLong()))
        .thenReturn(Optional.of(PlanTier.BUSINESS));
  }

  // ── invite ─────────────────────────────────────────────────────────────

  @Test
  void inviteCreatesPlaceholderUserWhenEmailUnknown() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("owner"));
    when(users.findIdByEmail("new@example.com")).thenReturn(Optional.empty());
    when(users.createPlaceholder("new@example.com")).thenReturn(INVITEE);
    when(membershipWrites.addMember(ORG, INVITEE, "member")).thenReturn(true);
    when(memberships.listMembersOf(ORG))
        .thenReturn(List.of(new Member(INVITEE, "new@example.com", null, "member", T)));

    Member out = service.invite(ACTOR, ORG, "  new@example.com ", "MEMBER");

    assertThat(out.userId()).isEqualTo(INVITEE);
    assertThat(out.role()).isEqualTo("member");
    verify(users).createPlaceholder("new@example.com");
    verify(membershipWrites).addMember(ORG, INVITEE, "member");
    verify(inviteEmails).send("new@example.com", ORG);
  }

  @Test
  void inviteReusesExistingUserWhenEmailKnown() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("owner"));
    when(users.findIdByEmail("known@example.com")).thenReturn(Optional.of(INVITEE));
    when(membershipWrites.addMember(ORG, INVITEE, "admin")).thenReturn(true);
    when(memberships.listMembersOf(ORG))
        .thenReturn(List.of(new Member(INVITEE, "known@example.com", "Bob", "admin", T)));

    service.invite(ACTOR, ORG, "known@example.com", "admin");

    verify(users, never()).createPlaceholder(anyString());
    verify(membershipWrites).addMember(ORG, INVITEE, "admin");
  }

  @Test
  void inviteRejectsNonOwners() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("admin"));
    assertThatThrownBy(() -> service.invite(ACTOR, ORG, "x@y.com", "member"))
        .isInstanceOf(MemberAccessDeniedException.class)
        .hasMessageContaining("owner");
    verify(membershipWrites, never()).addMember(anyLong(), any(), anyString());
    verify(inviteEmails, never()).send(anyString(), anyLong());
  }

  @Test
  void inviteRejectsAlreadyMember() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("owner"));
    when(users.findIdByEmail("dup@example.com")).thenReturn(Optional.of(INVITEE));
    when(membershipWrites.addMember(ORG, INVITEE, "member")).thenReturn(false);
    assertThatThrownBy(() -> service.invite(ACTOR, ORG, "dup@example.com", "member"))
        .isInstanceOf(DuplicateMemberException.class);
    verify(inviteEmails, never()).send(anyString(), anyLong());
  }

  @Test
  void inviteRejectsBadEmail() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("owner"));
    assertThatThrownBy(() -> service.invite(ACTOR, ORG, "no-at-sign", "member"))
        .isInstanceOf(InvalidMemberException.class)
        .hasMessageContaining("valid");
    assertThatThrownBy(() -> service.invite(ACTOR, ORG, "two@@signs.com", "member"))
        .isInstanceOf(InvalidMemberException.class);
    assertThatThrownBy(() -> service.invite(ACTOR, ORG, "  ", "member"))
        .isInstanceOf(InvalidMemberException.class);
    verify(membershipWrites, never()).addMember(anyLong(), any(), anyString());
  }

  @Test
  void inviteRejectsBadRole() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("owner"));
    assertThatThrownBy(() -> service.invite(ACTOR, ORG, "x@y.com", "superuser"))
        .isInstanceOf(InvalidMemberException.class)
        .hasMessageContaining("role");
  }

  // ── changeRole ─────────────────────────────────────────────────────────

  @Test
  void changeRoleAllowsOwnerToPromoteMember() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("owner"));
    when(memberships.userRoleInOrg(INVITEE, ORG)).thenReturn(Optional.of("member"));
    when(membershipWrites.updateRole(ORG, INVITEE, "owner")).thenReturn(true);
    when(memberships.listMembersOf(ORG))
        .thenReturn(List.of(new Member(INVITEE, "x@y.com", null, "owner", T)));

    Member out = service.changeRole(ACTOR, ORG, INVITEE, "owner");

    assertThat(out.role()).isEqualTo("owner");
    verify(membershipWrites).updateRole(ORG, INVITEE, "owner");
  }

  @Test
  void changeRoleRejectsNonOwners() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("member"));
    assertThatThrownBy(() -> service.changeRole(ACTOR, ORG, INVITEE, "owner"))
        .isInstanceOf(MemberAccessDeniedException.class);
    verify(membershipWrites, never()).updateRole(anyLong(), any(), anyString());
  }

  @Test
  void changeRoleNoOpWhenSameRole() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("owner"));
    when(memberships.userRoleInOrg(INVITEE, ORG)).thenReturn(Optional.of("admin"));
    when(memberships.listMembersOf(ORG))
        .thenReturn(List.of(new Member(INVITEE, "x@y.com", null, "admin", T)));

    service.changeRole(ACTOR, ORG, INVITEE, "admin");

    verify(membershipWrites, never()).updateRole(anyLong(), any(), anyString());
  }

  @Test
  void changeRoleBlocksLastOwnerDemotion() {
    // Actor IS the target — both lookups return "owner". Only the actor owns the org, so
    // demoting them to member must trip the last-owner guard.
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("owner"));
    when(memberships.countOwnersOf(ORG)).thenReturn(1);
    assertThatThrownBy(() -> service.changeRole(ACTOR, ORG, ACTOR, "member"))
        .isInstanceOf(LastOwnerException.class);
    verify(membershipWrites, never()).updateRole(anyLong(), any(), anyString());
  }

  @Test
  void changeRoleAllowsLastOwnerToStayOwner() {
    // Promoting an owner to owner is a no-op handled by the same-role early-return; doesn't trip
    // the last-owner guard. Sanity test.
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("owner"));
    when(memberships.listMembersOf(ORG))
        .thenReturn(List.of(new Member(ACTOR, "x@y.com", null, "owner", T)));

    service.changeRole(ACTOR, ORG, ACTOR, "owner");

    verify(memberships, never()).countOwnersOf(anyLong());
  }

  @Test
  void changeRoleAllowsDemotionWhenMultipleOwners() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("owner"));
    when(memberships.userRoleInOrg(OTHER_OWNER, ORG)).thenReturn(Optional.of("owner"));
    when(memberships.countOwnersOf(ORG)).thenReturn(2);
    when(membershipWrites.updateRole(ORG, OTHER_OWNER, "member")).thenReturn(true);
    when(memberships.listMembersOf(ORG))
        .thenReturn(List.of(new Member(OTHER_OWNER, "x@y.com", null, "member", T)));

    service.changeRole(ACTOR, ORG, OTHER_OWNER, "member");

    verify(membershipWrites).updateRole(ORG, OTHER_OWNER, "member");
  }

  @Test
  void changeRoleNotFoundForNonMember() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("owner"));
    when(memberships.userRoleInOrg(INVITEE, ORG)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.changeRole(ACTOR, ORG, INVITEE, "member"))
        .isInstanceOf(MemberNotFoundException.class);
  }

  // ── remove ─────────────────────────────────────────────────────────────

  @Test
  void removeAllowsOwnerToRemoveOthers() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("owner"));
    when(memberships.userRoleInOrg(INVITEE, ORG)).thenReturn(Optional.of("member"));
    when(membershipWrites.removeMember(ORG, INVITEE)).thenReturn(true);

    service.remove(ACTOR, ORG, INVITEE);

    verify(membershipWrites).removeMember(ORG, INVITEE);
  }

  @Test
  void removeAllowsSelfLeave() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("member"));
    when(membershipWrites.removeMember(ORG, ACTOR)).thenReturn(true);

    service.remove(ACTOR, ORG, ACTOR);

    verify(membershipWrites).removeMember(ORG, ACTOR);
  }

  @Test
  void removeRejectsNonOwnerRemovingOthers() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("member"));
    assertThatThrownBy(() -> service.remove(ACTOR, ORG, INVITEE))
        .isInstanceOf(MemberAccessDeniedException.class);
    verify(membershipWrites, never()).removeMember(anyLong(), any());
  }

  @Test
  void removeBlocksLastOwnerLeave() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("owner"));
    when(memberships.countOwnersOf(ORG)).thenReturn(1);
    assertThatThrownBy(() -> service.remove(ACTOR, ORG, ACTOR))
        .isInstanceOf(LastOwnerException.class);
    verify(membershipWrites, never()).removeMember(anyLong(), any());
  }

  @Test
  void removeNotFoundForNonMember() {
    when(memberships.userRoleInOrg(ACTOR, ORG)).thenReturn(Optional.of("owner"));
    when(memberships.userRoleInOrg(INVITEE, ORG)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.remove(ACTOR, ORG, INVITEE))
        .isInstanceOf(MemberNotFoundException.class);
  }

  // ── list ───────────────────────────────────────────────────────────────

  @Test
  void listDelegatesToRepo() {
    List<Member> expected = List.of(new Member(ACTOR, "a@b.com", "A", "owner", T));
    when(memberships.listMembersOf(ORG)).thenReturn(expected);
    assertThat(service.list(ORG)).isEqualTo(expected);
  }
}
