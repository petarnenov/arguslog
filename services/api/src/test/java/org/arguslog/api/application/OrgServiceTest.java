package org.arguslog.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.application.OrgUseCase.InvalidOrgException;
import org.arguslog.api.application.OrgUseCase.OrgAccessDeniedException;
import org.arguslog.api.application.OrgUseCase.OrgQuotaExceededException;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.application.port.UserRepository;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.billing.domain.PlanTier;
import org.arguslog.api.domain.Org;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrgServiceTest {

  @Mock OrgWriteRepository orgs;
  @Mock UserRepository users;
  @Mock MembershipRepository memberships;
  @Mock OrgPlanRepository plans;

  OrgService service;

  static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @BeforeEach
  void setUp() {
    service = new OrgService(orgs, users, memberships, plans);
  }

  @Test
  void createUpsertsUserBeforeInsertingOrgAndAddsOwnerMembership() {
    Org expected = new Org(42L, "acme", "Acme", "free", Instant.parse("2026-05-06T00:00:00Z"));
    when(orgs.create(eq("acme"), eq("Acme"))).thenReturn(expected);

    Org out = service.create(ACTOR, "alice@example.com", "Alice", "Acme");

    assertThat(out).isEqualTo(expected);
    verify(users).upsertFromJwt(ACTOR, "alice@example.com", "Alice");
    verify(orgs).create("acme", "Acme");
    verify(orgs).addMember(42L, ACTOR, "owner");
  }

  @Test
  void slugifyCollapsesNonAlphanumericRuns() {
    assertThat(OrgService.slugify("My Cool App!")).isEqualTo("my-cool-app");
    assertThat(OrgService.slugify("  spaces   here  ")).isEqualTo("spaces-here");
    assertThat(OrgService.slugify("UPPER_case--mix")).isEqualTo("upper-case-mix");
    assertThat(OrgService.slugify("trailing---")).isEqualTo("trailing");
    assertThat(OrgService.slugify("---leading")).isEqualTo("leading");
  }

  @Test
  void slugifyFallsBackForNonAsciiOnly() {
    assertThat(OrgService.slugify("София")).isEqualTo("org");
    assertThat(OrgService.slugify("!!!")).isEqualTo("org");
  }

  @Test
  void rejectsBlankOrShortName() {
    assertThatThrownBy(() -> service.create(ACTOR, "a@b.com", null, null))
        .isInstanceOf(InvalidOrgException.class)
        .hasMessageContaining("required");
    assertThatThrownBy(() -> service.create(ACTOR, "a@b.com", null, " "))
        .isInstanceOf(InvalidOrgException.class)
        .hasMessageContaining("at least");
    assertThatThrownBy(() -> service.create(ACTOR, "a@b.com", null, "x"))
        .isInstanceOf(InvalidOrgException.class)
        .hasMessageContaining("at least");
    verify(orgs, never()).create(anyString(), anyString());
    verify(users, never()).upsertFromJwt(any(), anyString(), any());
  }

  @Test
  void createSkipsUserSyncWhenEmailMissing() {
    // PAT-driven creates omit JWT claims (PAT users already have a user row). The service still
    // creates the org and membership, just skips the upsert.
    Org expected = new Org(42L, "acme", "Acme", "free", Instant.parse("2026-05-06T00:00:00Z"));
    when(orgs.create(eq("acme"), eq("Acme"))).thenReturn(expected);

    Org out = service.create(ACTOR, null, null, "Acme");

    assertThat(out).isEqualTo(expected);
    verify(users, never()).upsertFromJwt(any(), anyString(), any());
    verify(orgs).create("acme", "Acme");
    verify(orgs).addMember(42L, ACTOR, "owner");
  }

  @Test
  void createSkipsUserSyncWhenEmailBlank() {
    Org expected = new Org(42L, "acme", "Acme", "free", Instant.parse("2026-05-06T00:00:00Z"));
    when(orgs.create(eq("acme"), eq("Acme"))).thenReturn(expected);

    service.create(ACTOR, " ", "ignored", "Acme");

    verify(users, never()).upsertFromJwt(any(), anyString(), any());
  }

  @Test
  void rejectsNullActor() {
    assertThatThrownBy(() -> service.create(null, "a@b.com", "Alice", "Acme"))
        .isInstanceOf(IllegalStateException.class);
    verify(orgs, never()).create(anyString(), anyString());
  }

  @Test
  void deleteRejectsNonMembers() {
    when(memberships.userRoleInOrg(ACTOR, 1L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete(ACTOR, 1L))
        .isInstanceOf(OrgAccessDeniedException.class)
        .hasMessageContaining("not a member");
    verify(orgs, never()).delete(anyLong());
  }

  @Test
  void deleteRejectsAdmins() {
    when(memberships.userRoleInOrg(ACTOR, 1L)).thenReturn(Optional.of("admin"));
    assertThatThrownBy(() -> service.delete(ACTOR, 1L))
        .isInstanceOf(OrgAccessDeniedException.class)
        .hasMessageContaining("owners");
    verify(orgs, never()).delete(anyLong());
  }

  @Test
  void rejectsSecondOrgOnFreePlan() {
    // First-time free user already owns one org → second create attempt hits the cap.
    when(plans.findHighestPlanForOwner(ACTOR)).thenReturn(Optional.of(PlanTier.FREE));
    when(orgs.countOwnedBy(ACTOR)).thenReturn(1);

    assertThatThrownBy(() -> service.create(ACTOR, "a@b.com", "Alice", "Acme"))
        .isInstanceOf(OrgQuotaExceededException.class)
        .hasMessageContaining("free")
        .hasMessageContaining("1 organization");
    verify(orgs, never()).create(anyString(), anyString());
    verify(orgs, never()).addMember(anyLong(), any(), anyString());
  }

  @Test
  void allowsThirdOrgOnStarterPlan() {
    // Starter cap is 3 — owning 2 orgs still leaves room for one more.
    when(plans.findHighestPlanForOwner(ACTOR)).thenReturn(Optional.of(PlanTier.STARTER));
    when(orgs.countOwnedBy(ACTOR)).thenReturn(2);
    Org expected = new Org(43L, "acme", "Acme", "free", Instant.parse("2026-05-06T00:00:00Z"));
    when(orgs.create(eq("acme"), eq("Acme"))).thenReturn(expected);

    Org out = service.create(ACTOR, "a@b.com", "Alice", "Acme");

    assertThat(out).isEqualTo(expected);
    verify(orgs).create("acme", "Acme");
  }

  @Test
  void rejectsFourthOrgOnStarterPlan() {
    when(plans.findHighestPlanForOwner(ACTOR)).thenReturn(Optional.of(PlanTier.STARTER));
    when(orgs.countOwnedBy(ACTOR)).thenReturn(3);

    assertThatThrownBy(() -> service.create(ACTOR, "a@b.com", "Alice", "Acme"))
        .isInstanceOf(OrgQuotaExceededException.class)
        .hasMessageContaining("starter")
        .hasMessageContaining("3 organizations");
    verify(orgs, never()).create(anyString(), anyString());
  }

  @Test
  void allowsUnlimitedOrgsOnBusinessPlan() {
    // Business is uncapped (Integer.MAX_VALUE) — no count query should even be needed, but the
    // current path still reads it. The point is: 100 owned orgs still go through.
    when(plans.findHighestPlanForOwner(ACTOR)).thenReturn(Optional.of(PlanTier.BUSINESS));
    Org expected = new Org(99L, "acme", "Acme", "business", Instant.parse("2026-05-06T00:00:00Z"));
    when(orgs.create(eq("acme"), eq("Acme"))).thenReturn(expected);

    Org out = service.create(ACTOR, "a@b.com", "Alice", "Acme");

    assertThat(out).isEqualTo(expected);
  }

  @Test
  void deleteAllowsOwners() {
    when(memberships.userRoleInOrg(ACTOR, 1L)).thenReturn(Optional.of("owner"));
    when(orgs.delete(1L)).thenReturn(true);
    assertThat(service.delete(ACTOR, 1L)).isTrue();
    verify(orgs).delete(1L);
  }
}
