package org.arguslog.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.arguslog.api.application.ProjectUseCase.InvalidProjectException;
import org.arguslog.api.application.ProjectUseCase.ProjectAccessDeniedException;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.PlatformRepository;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.domain.Project;
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

  ProjectService service;

  @BeforeEach
  void setUp() {
    service = new ProjectService(projects, memberships, platforms);
    // Default to the four shipped SDKs for tests that hit the platform check; lenient because
    // archive-path tests don't reach platform validation at all.
    org.mockito.Mockito.lenient()
        .when(platforms.enabledSlugs())
        .thenReturn(Set.of("javascript", "react", "react-native", "java-spring"));
  }

  @Test
  void createDerivesSlugAndPassesPlatform() {
    Project expected =
        new Project(
            7L, 1L, "my-app", "My App", "javascript", Instant.parse("2026-05-06T00:00:00Z"));
    when(projects.create(eq(1L), eq("my-app"), eq("My App"), eq("javascript")))
        .thenReturn(expected);

    Project out = service.create(1L, "My App", "javascript");

    assertThat(out).isEqualTo(expected);
    verify(projects).create(1L, "my-app", "My App", "javascript");
  }

  @Test
  void rejectsBlankName() {
    assertThatThrownBy(() -> service.create(1L, " ", "javascript"))
        .isInstanceOf(InvalidProjectException.class)
        .hasMessageContaining("at least");
    verify(projects, never()).create(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  void rejectsUnknownPlatform() {
    assertThatThrownBy(() -> service.create(1L, "Acme", "cobol"))
        .isInstanceOf(InvalidProjectException.class)
        .hasMessageContaining("platform");
    verify(projects, never()).create(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  void acceptsAllKnownPlatforms() {
    Project p = new Project(1L, 1L, "x", "x", "x", Instant.EPOCH);
    when(projects.create(anyLong(), anyString(), anyString(), anyString())).thenReturn(p);
    service.create(1L, "ok", "javascript");
    service.create(1L, "ok", "react");
    service.create(1L, "ok", "react-native");
    service.create(1L, "ok", "java-spring");
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
}
