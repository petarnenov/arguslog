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
import java.util.Set;
import java.util.UUID;
import org.arguslog.api.application.ProjectService.GitRef;
import org.arguslog.api.application.ProjectUseCase.InvalidProjectException;
import org.arguslog.api.application.ProjectUseCase.ProjectAccessDeniedException;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.PlatformRepository;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.domain.GitProvider;
import org.arguslog.api.domain.Project;
import org.arguslog.api.tier.application.port.TierLookupRepository;
import org.arguslog.billing.PlanTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

  @Mock ProjectWriteRepository projects;
  @Mock MembershipRepository memberships;
  @Mock PlatformRepository platforms;
  @Mock TierLookupRepository plans;

  ProjectService service;

  @BeforeEach
  void setUp() {
    service = new ProjectService(projects, memberships, platforms, plans);
    org.mockito.Mockito.lenient()
        .when(platforms.enabledSlugs())
        .thenReturn(Set.of("javascript", "react", "react-native", "java-spring"));
    org.mockito.Mockito.lenient()
        .when(plans.findTier(org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(Optional.of(PlanTier.PLATINUM));
  }

  @Test
  void createDerivesSlugAndPassesPlatform() {
    Project expected = project(7L, "my-app", "My App", "javascript", null, null);
    when(projects.create(
            eq(1L),
            eq("my-app"),
            eq("My App"),
            eq("javascript"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull()))
        .thenReturn(expected);

    Project out = service.create(1L, "My App", "javascript", null, null);

    assertThat(out).isEqualTo(expected);
    verify(projects).create(1L, "my-app", "My App", "javascript", null, null);
  }

  @Test
  void createNormalizesGithubRepoFromUrl() {
    Project expected =
        project(8L, "acme", "Acme", "javascript", GitProvider.GITHUB, "acme/widgets");
    when(projects.create(
            eq(1L),
            eq("acme"),
            eq("Acme"),
            eq("javascript"),
            eq(GitProvider.GITHUB),
            eq("acme/widgets")))
        .thenReturn(expected);

    Project out =
        service.create(1L, "Acme", "javascript", null, "https://github.com/acme/widgets.git");

    assertThat(out.gitProvider()).isEqualTo(GitProvider.GITHUB);
    assertThat(out.gitRepo()).isEqualTo("acme/widgets");
    verify(projects).create(1L, "acme", "Acme", "javascript", GitProvider.GITHUB, "acme/widgets");
  }

  @Test
  void createNormalizesGitlabUrlWithNestedGroup() {
    Project expected =
        project(9L, "acme", "Acme", "javascript", GitProvider.GITLAB, "group/sub/widgets");
    when(projects.create(
            anyLong(),
            anyString(),
            anyString(),
            anyString(),
            eq(GitProvider.GITLAB),
            eq("group/sub/widgets")))
        .thenReturn(expected);

    Project out =
        service.create(1L, "Acme", "javascript", null, "https://gitlab.com/group/sub/widgets");

    assertThat(out.gitProvider()).isEqualTo(GitProvider.GITLAB);
    assertThat(out.gitRepo()).isEqualTo("group/sub/widgets");
  }

  @Test
  void createRejectsInvalidGitRepoForProvider() {
    assertThatThrownBy(
            () -> service.create(1L, "Acme", "javascript", GitProvider.GITHUB, "not a repo"))
        .isInstanceOf(InvalidProjectException.class)
        .hasMessageContaining("gitRepo must look like");
    verify(projects, never())
        .create(anyLong(), anyString(), anyString(), anyString(), any(), anyString());
  }

  @Test
  void createRejectsHintProviderConflictingWithUrlHost() {
    assertThatThrownBy(
            () ->
                service.create(
                    1L, "Acme", "javascript", GitProvider.GITHUB, "https://gitlab.com/group/proj"))
        .isInstanceOf(InvalidProjectException.class)
        .hasMessageContaining("gitProvider is github");
  }

  @Test
  void createRejectsRepoWithoutProviderWhenNoUrlPrefix() {
    assertThatThrownBy(() -> service.create(1L, "Acme", "javascript", null, "owner/repo"))
        .isInstanceOf(InvalidProjectException.class)
        .hasMessageContaining("gitProvider is required");
  }

  @Test
  void rejectsBlankName() {
    assertThatThrownBy(() -> service.create(1L, " ", "javascript", null, null))
        .isInstanceOf(InvalidProjectException.class)
        .hasMessageContaining("at least");
    verify(projects, never())
        .create(anyLong(), anyString(), anyString(), anyString(), any(), anyString());
  }

  @Test
  void rejectsUnknownPlatform() {
    assertThatThrownBy(() -> service.create(1L, "Acme", "cobol", null, null))
        .isInstanceOf(InvalidProjectException.class)
        .hasMessageContaining("platform");
    verify(projects, never())
        .create(anyLong(), anyString(), anyString(), anyString(), any(), anyString());
  }

  @Test
  void acceptsAllKnownPlatforms() {
    Project p = project(1L, "x", "x", "x", null, null);
    when(projects.create(
            anyLong(),
            anyString(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull()))
        .thenReturn(p);
    service.create(1L, "ok", "javascript", null, null);
    service.create(1L, "ok", "react", null, null);
    service.create(1L, "ok", "react-native", null, null);
    service.create(1L, "ok", "java-spring", null, null);
  }

  @Test
  void archiveRejectsNonMembers() {
    UUID actor = UUID.fromString("11111111-1111-1111-1111-111111111111");
    when(memberships.userRoleInOrg(actor, 1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.archive(actor, 1L, 7L))
        .isInstanceOf(ProjectAccessDeniedException.class)
        .hasMessageContaining("not a member");
    verify(projects, never()).archive(anyLong(), anyLong());
  }

  @Test
  void archiveRejectsPlainMembers() {
    UUID actor = UUID.fromString("22222222-2222-2222-2222-222222222222");
    when(memberships.userRoleInOrg(actor, 1L)).thenReturn(Optional.of("member"));

    assertThatThrownBy(() -> service.archive(actor, 1L, 7L))
        .isInstanceOf(ProjectAccessDeniedException.class)
        .hasMessageContaining("owners and admins");
    verify(projects, never()).archive(anyLong(), anyLong());
  }

  @Test
  void archiveAllowsOwnersAndAdmins() {
    UUID actor = UUID.fromString("33333333-3333-3333-3333-333333333333");
    when(memberships.userRoleInOrg(actor, 1L)).thenReturn(Optional.of("admin"));
    when(projects.archive(1L, 7L)).thenReturn(true);

    assertThat(service.archive(actor, 1L, 7L)).isTrue();
    verify(projects).archive(1L, 7L);
  }

  @Test
  void renameAllowsOwnersAndAdmins() {
    UUID actor = UUID.fromString("44444444-4444-4444-4444-444444444444");
    Project renamed = project(7L, "my-app", "Renamed", "javascript", null, null);
    when(memberships.userRoleInOrg(actor, 1L)).thenReturn(Optional.of("admin"));
    when(projects.rename(1L, 7L, "Renamed")).thenReturn(Optional.of(renamed));

    assertThat(service.rename(actor, 1L, 7L, "  Renamed  ")).contains(renamed);
    verify(projects).rename(1L, 7L, "Renamed");
  }

  @Test
  void updateGitRepoNormalizesAndPersists() {
    UUID actor = UUID.fromString("88888888-8888-8888-8888-888888888888");
    Project updated =
        project(7L, "my-app", "My App", "javascript", GitProvider.GITHUB, "acme/widgets");
    when(memberships.userRoleInOrg(actor, 1L)).thenReturn(Optional.of("owner"));
    when(projects.updateGitRepo(1L, 7L, GitProvider.GITHUB, "acme/widgets"))
        .thenReturn(Optional.of(updated));

    assertThat(service.updateGitRepo(actor, 1L, 7L, GitProvider.GITHUB, "  acme/widgets  "))
        .contains(updated);
    verify(projects).updateGitRepo(1L, 7L, GitProvider.GITHUB, "acme/widgets");
  }

  @Test
  void updateGitRepoEmptyStringsClearLink() {
    UUID actor = UUID.fromString("99999999-9999-9999-9999-999999999999");
    Project cleared = project(7L, "my-app", "My App", "javascript", null, null);
    when(memberships.userRoleInOrg(actor, 1L)).thenReturn(Optional.of("admin"));
    when(projects.updateGitRepo(1L, 7L, null, null)).thenReturn(Optional.of(cleared));

    assertThat(service.updateGitRepo(actor, 1L, 7L, null, "")).contains(cleared);
    verify(projects).updateGitRepo(1L, 7L, null, null);
  }

  @Test
  void updateGitRepoRejectsPlainMembers() {
    UUID actor = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    when(memberships.userRoleInOrg(actor, 1L)).thenReturn(Optional.of("member"));

    assertThatThrownBy(
            () -> service.updateGitRepo(actor, 1L, 7L, GitProvider.GITHUB, "acme/widgets"))
        .isInstanceOf(ProjectAccessDeniedException.class)
        .hasMessageContaining("owners and admins");
    verify(projects, never()).updateGitRepo(anyLong(), anyLong(), any(), anyString());
  }

  @Test
  void renameRejectsPlainMembers() {
    UUID actor = UUID.fromString("55555555-5555-5555-5555-555555555555");
    when(memberships.userRoleInOrg(actor, 1L)).thenReturn(Optional.of("member"));

    assertThatThrownBy(() -> service.rename(actor, 1L, 7L, "Renamed"))
        .isInstanceOf(ProjectAccessDeniedException.class)
        .hasMessageContaining("owners and admins");
    verify(projects, never()).rename(anyLong(), anyLong(), anyString());
  }

  @Test
  void renameRejectsNonMembers() {
    UUID actor = UUID.fromString("66666666-6666-6666-6666-666666666666");
    when(memberships.userRoleInOrg(actor, 1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.rename(actor, 1L, 7L, "Renamed"))
        .isInstanceOf(ProjectAccessDeniedException.class)
        .hasMessageContaining("not a member");
    verify(projects, never()).rename(anyLong(), anyLong(), anyString());
  }

  @Test
  void renameRejectsBlankOrShortName() {
    UUID actor = UUID.fromString("77777777-7777-7777-7777-777777777777");
    assertThatThrownBy(() -> service.rename(actor, 1L, 7L, "x"))
        .isInstanceOf(InvalidProjectException.class)
        .hasMessageContaining("at least");
    assertThatThrownBy(() -> service.rename(actor, 1L, 7L, null))
        .isInstanceOf(InvalidProjectException.class)
        .hasMessageContaining("required");
    verify(projects, never()).rename(anyLong(), anyLong(), anyString());
  }

  @Test
  void normalizeGitRefAcceptsCanonicalAndPasteShapes() {
    // Both null/blank → cleared
    assertThat(ProjectService.normalizeGitRef(null, null)).isNull();
    assertThat(ProjectService.normalizeGitRef(null, "")).isNull();
    assertThat(ProjectService.normalizeGitRef(null, "  ")).isNull();

    // Canonical with provider hint
    assertThat(ProjectService.normalizeGitRef(GitProvider.GITHUB, "acme/widgets"))
        .isEqualTo(new GitRef(GitProvider.GITHUB, "acme/widgets"));
    assertThat(ProjectService.normalizeGitRef(GitProvider.GITLAB, "group/sub/proj"))
        .isEqualTo(new GitRef(GitProvider.GITLAB, "group/sub/proj"));

    // URL paste — provider auto-detected
    assertThat(ProjectService.normalizeGitRef(null, "https://github.com/acme/widgets.git"))
        .isEqualTo(new GitRef(GitProvider.GITHUB, "acme/widgets"));
    assertThat(ProjectService.normalizeGitRef(null, "https://gitlab.com/group/sub/proj/"))
        .isEqualTo(new GitRef(GitProvider.GITLAB, "group/sub/proj"));

    // SSH clone strings
    assertThat(ProjectService.normalizeGitRef(null, "git@github.com:acme/widgets.git"))
        .isEqualTo(new GitRef(GitProvider.GITHUB, "acme/widgets"));
    assertThat(ProjectService.normalizeGitRef(null, "git@gitlab.com:group/proj.git"))
        .isEqualTo(new GitRef(GitProvider.GITLAB, "group/proj"));

    // Hint + URL agreeing
    assertThat(
            ProjectService.normalizeGitRef(GitProvider.GITHUB, "https://github.com/acme/widgets"))
        .isEqualTo(new GitRef(GitProvider.GITHUB, "acme/widgets"));
  }

  @Test
  void normalizeGitRefRejectsGarbage() {
    assertThatThrownBy(() -> ProjectService.normalizeGitRef(GitProvider.GITHUB, "no-slash"))
        .isInstanceOf(InvalidProjectException.class);
    assertThatThrownBy(() -> ProjectService.normalizeGitRef(GitProvider.GITHUB, "/leading-slash"))
        .isInstanceOf(InvalidProjectException.class);
    assertThatThrownBy(() -> ProjectService.normalizeGitRef(GitProvider.GITHUB, ".dotstart/repo"))
        .isInstanceOf(InvalidProjectException.class);
    // GitHub allows exactly one slash; nested paths belong to GitLab.
    assertThatThrownBy(() -> ProjectService.normalizeGitRef(GitProvider.GITHUB, "owner/sub/repo"))
        .isInstanceOf(InvalidProjectException.class);
    // Provider hint missing AND no URL prefix → can't derive
    assertThatThrownBy(() -> ProjectService.normalizeGitRef(null, "acme/widgets"))
        .isInstanceOf(InvalidProjectException.class)
        .hasMessageContaining("gitProvider is required");
    // Provider hint conflicts with URL host
    assertThatThrownBy(
            () ->
                ProjectService.normalizeGitRef(GitProvider.GITHUB, "https://gitlab.com/group/proj"))
        .isInstanceOf(InvalidProjectException.class)
        .hasMessageContaining("gitProvider is github");
  }

  private static Project project(
      long id, String slug, String name, String platform, GitProvider provider, String repo) {
    return new Project(
        id, 1L, slug, name, platform, Instant.parse("2026-05-17T00:00:00Z"), provider, repo);
  }
}
