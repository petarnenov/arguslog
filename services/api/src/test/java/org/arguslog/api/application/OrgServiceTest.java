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
import org.arguslog.api.domain.Org;
import org.arguslog.api.tier.application.port.TierLookupRepository;
import org.arguslog.billing.PlanTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrgServiceTest {

  @Mock OrgWriteRepository orgs;
  @Mock MembershipRepository memberships;
  @Mock TierLookupRepository tiers;

  OrgService service;

  static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @BeforeEach
  void setUp() {
    service = new OrgService(orgs, memberships, tiers);
  }

  @Test
  void createInsertsOrgAndAddsOwnerMembership() {
    Org expected = new Org(42L, "acme", "Acme", "regular", Instant.parse("2026-05-06T00:00:00Z"));
    when(orgs.create(eq("acme"), eq("Acme"), eq("regular"))).thenReturn(expected);

    Org out = service.create(ACTOR, "Acme");

    assertThat(out).isEqualTo(expected);
    verify(orgs).create("acme", "Acme", "regular");
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
    assertThatThrownBy(() -> service.create(ACTOR, null))
        .isInstanceOf(InvalidOrgException.class)
        .hasMessageContaining("required");
    assertThatThrownBy(() -> service.create(ACTOR, " "))
        .isInstanceOf(InvalidOrgException.class)
        .hasMessageContaining("at least");
    assertThatThrownBy(() -> service.create(ACTOR, "x"))
        .isInstanceOf(InvalidOrgException.class)
        .hasMessageContaining("at least");
    verify(orgs, never()).create(anyString(), anyString(), anyString());
  }

  @Test
  void rejectsNullActor() {
    assertThatThrownBy(() -> service.create(null, "Acme"))
        .isInstanceOf(IllegalStateException.class);
    verify(orgs, never()).create(anyString(), anyString(), anyString());
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
  void rejectsSecondOrgOnRegularTier() {
    when(tiers.findTierForUser(ACTOR)).thenReturn(Optional.of(PlanTier.REGULAR));
    when(orgs.countOwnedBy(ACTOR)).thenReturn(1);

    assertThatThrownBy(() -> service.create(ACTOR, "Acme"))
        .isInstanceOf(OrgQuotaExceededException.class)
        .hasMessageContaining("regular")
        .hasMessageContaining("1 organization");
    verify(orgs, never()).create(anyString(), anyString(), anyString());
    verify(orgs, never()).addMember(anyLong(), any(), anyString());
  }

  @Test
  void allowsThirdOrgOnSilverTier() {
    when(tiers.findTierForUser(ACTOR)).thenReturn(Optional.of(PlanTier.SILVER));
    when(orgs.countOwnedBy(ACTOR)).thenReturn(2);
    Org expected = new Org(43L, "acme", "Acme", "silver", Instant.parse("2026-05-06T00:00:00Z"));
    when(orgs.create(eq("acme"), eq("Acme"), eq("silver"))).thenReturn(expected);

    Org out = service.create(ACTOR, "Acme");

    assertThat(out).isEqualTo(expected);
    verify(orgs).create("acme", "Acme", "silver");
  }

  @Test
  void rejectsFourthOrgOnSilverTier() {
    when(tiers.findTierForUser(ACTOR)).thenReturn(Optional.of(PlanTier.SILVER));
    when(orgs.countOwnedBy(ACTOR)).thenReturn(3);

    assertThatThrownBy(() -> service.create(ACTOR, "Acme"))
        .isInstanceOf(OrgQuotaExceededException.class)
        .hasMessageContaining("silver")
        .hasMessageContaining("3 organizations");
    verify(orgs, never()).create(anyString(), anyString(), anyString());
  }

  @Test
  void allowsUnlimitedOrgsOnPlatinumTier() {
    when(tiers.findTierForUser(ACTOR)).thenReturn(Optional.of(PlanTier.PLATINUM));
    Org expected = new Org(99L, "acme", "Acme", "platinum", Instant.parse("2026-05-06T00:00:00Z"));
    when(orgs.create(eq("acme"), eq("Acme"), eq("platinum"))).thenReturn(expected);

    Org out = service.create(ACTOR, "Acme");

    assertThat(out).isEqualTo(expected);
  }

  @Test
  void newOrgInheritsGoldTierFromCreator() {
    when(tiers.findTierForUser(ACTOR)).thenReturn(Optional.of(PlanTier.GOLD));
    when(orgs.countOwnedBy(ACTOR)).thenReturn(1);
    Org expected = new Org(50L, "acme", "Acme", "gold", Instant.parse("2026-05-11T00:00:00Z"));
    when(orgs.create(eq("acme"), eq("Acme"), eq("gold"))).thenReturn(expected);

    Org out = service.create(ACTOR, "Acme");

    assertThat(out).isEqualTo(expected);
    verify(orgs).create("acme", "Acme", "gold");
    verify(orgs).addMember(50L, ACTOR, "owner");
  }

  @Test
  void newOrgDefaultsToRegularForFirstTimeCreator() {
    when(tiers.findTierForUser(ACTOR)).thenReturn(Optional.empty());
    when(orgs.countOwnedBy(ACTOR)).thenReturn(0);
    Org expected = new Org(51L, "acme", "Acme", "regular", Instant.parse("2026-05-11T00:00:00Z"));
    when(orgs.create(eq("acme"), eq("Acme"), eq("regular"))).thenReturn(expected);

    Org out = service.create(ACTOR, "Acme");

    assertThat(out).isEqualTo(expected);
    verify(orgs).create("acme", "Acme", "regular");
  }

  @Test
  void deleteAllowsOwners() {
    when(memberships.userRoleInOrg(ACTOR, 1L)).thenReturn(Optional.of("owner"));
    when(orgs.delete(1L)).thenReturn(true);
    assertThat(service.delete(ACTOR, 1L)).isTrue();
    verify(orgs).delete(1L);
  }

  @Test
  void renameAllowsOwners() {
    Org renamed =
        new Org(1L, "acme", "Acme Renamed", "regular", Instant.parse("2026-05-13T00:00:00Z"));
    when(memberships.userRoleInOrg(ACTOR, 1L)).thenReturn(Optional.of("owner"));
    when(orgs.rename(1L, "Acme Renamed")).thenReturn(Optional.of(renamed));

    Optional<Org> out = service.rename(ACTOR, 1L, "  Acme Renamed  ");

    assertThat(out).contains(renamed);
    verify(orgs).rename(1L, "Acme Renamed");
  }

  @Test
  void renameRejectsAdmins() {
    when(memberships.userRoleInOrg(ACTOR, 1L)).thenReturn(Optional.of("admin"));
    assertThatThrownBy(() -> service.rename(ACTOR, 1L, "New Name"))
        .isInstanceOf(OrgAccessDeniedException.class)
        .hasMessageContaining("owners");
    verify(orgs, never()).rename(anyLong(), anyString());
  }

  @Test
  void renameRejectsNonMembers() {
    when(memberships.userRoleInOrg(ACTOR, 1L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.rename(ACTOR, 1L, "New Name"))
        .isInstanceOf(OrgAccessDeniedException.class)
        .hasMessageContaining("not a member");
    verify(orgs, never()).rename(anyLong(), anyString());
  }

  @Test
  void renameRejectsShortOrBlankName() {
    assertThatThrownBy(() -> service.rename(ACTOR, 1L, "x"))
        .isInstanceOf(InvalidOrgException.class)
        .hasMessageContaining("at least");
    assertThatThrownBy(() -> service.rename(ACTOR, 1L, null))
        .isInstanceOf(InvalidOrgException.class)
        .hasMessageContaining("required");
    verify(memberships, never()).userRoleInOrg(any(), anyLong());
    verify(orgs, never()).rename(anyLong(), anyString());
  }

  @Test
  void renameReturnsEmptyWhenOrgMissingAfterRoleCheck() {
    when(memberships.userRoleInOrg(ACTOR, 1L)).thenReturn(Optional.of("owner"));
    when(orgs.rename(1L, "New Name")).thenReturn(Optional.empty());

    assertThat(service.rename(ACTOR, 1L, "New Name")).isEmpty();
  }
}
