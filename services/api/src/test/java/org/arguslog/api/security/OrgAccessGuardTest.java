package org.arguslog.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.arguslog.api.application.port.MembershipRepository;
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
class OrgAccessGuardTest {

  @Mock MembershipRepository memberships;

  OrgAccessGuard guard;
  UUID userId;

  @BeforeEach
  void setUp() {
    guard = new OrgAccessGuard(memberships);
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
    when(memberships.userIsMemberOfOrg(userId, 1L)).thenReturn(true);
    boolean ok =
        guard.preHandle(requestWithOrgId("1"), new MockHttpServletResponse(), new Object());
    assertThat(ok).isTrue();
    assertThat(OrgContext.current()).contains(1L);
  }

  @Test
  void nonMemberIs404NotForbidden() {
    when(memberships.userIsMemberOfOrg(userId, 1L)).thenReturn(false);
    assertThatThrownBy(
            () ->
                guard.preHandle(requestWithOrgId("1"), new MockHttpServletResponse(), new Object()))
        .isInstanceOf(AccessException.class)
        .matches(e -> ((AccessException) e).status() == 404);
    assertThat(OrgContext.current()).isEmpty();
  }

  @Test
  void afterCompletionClearsOrgContext() {
    OrgContext.set(1L);
    guard.afterCompletion(
        new MockHttpServletRequest(),
        new MockHttpServletResponse(),
        new Object(),
        new RuntimeException("x"));
    assertThat(OrgContext.current()).isEmpty();
  }

  @Test
  void noAuthIsProgrammingError() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(
            () ->
                guard.preHandle(requestWithOrgId("1"), new MockHttpServletResponse(), new Object()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void nonUuidJwtSubjectIsProgrammingError() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("nope", "n/a", java.util.List.of()));
    assertThatThrownBy(
            () ->
                guard.preHandle(requestWithOrgId("1"), new MockHttpServletResponse(), new Object()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static HttpServletRequest requestWithOrgId(String value) {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("orgId", value));
    return req;
  }
}
