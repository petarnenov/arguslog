package org.arguslog.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.ProjectRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

@ExtendWith(MockitoExtension.class)
class ProjectAccessGuardTest {

  @Mock ProjectRepository projects;
  @Mock MembershipRepository memberships;

  ProjectAccessGuard guard;
  UUID userId;

  @BeforeEach
  void setUp() {
    guard = new ProjectAccessGuard(projects, memberships);
    userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(userId.toString(), "n/a", java.util.List.of()));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    OrgContext.clear();
  }

  @Test
  void allowsMemberAndPrimesOrgContext() {
    when(projects.findOrgIdForProject(101L)).thenReturn(OptionalLong.of(7L));
    when(memberships.userIsMemberOfOrg(userId, 7L)).thenReturn(true);

    boolean ok =
        guard.preHandle(requestWithProjectId("101"), new MockHttpServletResponse(), new Object());

    assertThat(ok).isTrue();
    assertThat(OrgContext.current()).contains(7L);
  }

  @Test
  void unknownProjectIsAccessException404() {
    when(projects.findOrgIdForProject(101L)).thenReturn(OptionalLong.empty());

    assertThatThrownBy(
            () ->
                guard.preHandle(
                    requestWithProjectId("101"), new MockHttpServletResponse(), new Object()))
        .isInstanceOf(AccessException.class)
        .matches(e -> ((AccessException) e).status() == 404);
    assertThat(OrgContext.current()).isEmpty();
  }

  @Test
  void nonMemberIsAccessExceptionAlso404() {
    // 404, not 403 — we never confirm a project's existence to non-members.
    when(projects.findOrgIdForProject(101L)).thenReturn(OptionalLong.of(7L));
    when(memberships.userIsMemberOfOrg(userId, 7L)).thenReturn(false);

    assertThatThrownBy(
            () ->
                guard.preHandle(
                    requestWithProjectId("101"), new MockHttpServletResponse(), new Object()))
        .isInstanceOf(AccessException.class)
        .matches(e -> ((AccessException) e).status() == 404);
    assertThat(OrgContext.current()).isEmpty();
  }

  @Test
  void afterCompletionClearsOrgContextEvenAfterFailure() {
    OrgContext.set(7L);
    guard.afterCompletion(
        new MockHttpServletRequest(),
        new MockHttpServletResponse(),
        new Object(),
        new RuntimeException("x"));
    assertThat(OrgContext.current()).isEmpty();
  }

  @Test
  void noAuthenticationIsProgrammingError() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(
            () ->
                guard.preHandle(
                    requestWithProjectId("101"), new MockHttpServletResponse(), new Object()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void nonUuidJwtSubjectIsProgrammingError() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("not-a-uuid", "n/a", java.util.List.of()));
    assertThatThrownBy(
            () ->
                guard.preHandle(
                    requestWithProjectId("101"), new MockHttpServletResponse(), new Object()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static HttpServletRequest requestWithProjectId(String value) {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("projectId", value));
    return req;
  }
}
