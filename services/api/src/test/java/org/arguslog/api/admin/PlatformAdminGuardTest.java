package org.arguslog.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.arguslog.api.admin.PlatformAdminGuard.AdminAccessDeniedException;
import org.arguslog.api.admin.config.PlatformAdminProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class PlatformAdminGuardTest {

  @AfterEach
  void clearAuth() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void requireAdminPassesWhenJwtEmailMatchesAllowlist() {
    SecurityContextHolder.getContext().setAuthentication(jwtToken("admin@arguslog.org"));
    PlatformAdminGuard guard =
        new PlatformAdminGuard(new PlatformAdminProperties(List.of("admin@arguslog.org")));
    assertThat(guard.requireAdmin()).isEqualTo("admin@arguslog.org");
  }

  @Test
  void requireAdminLowercasesIncomingEmail() {
    SecurityContextHolder.getContext().setAuthentication(jwtToken("Petar@Example.com"));
    PlatformAdminGuard guard =
        new PlatformAdminGuard(new PlatformAdminProperties(List.of("petar@example.com")));
    assertThat(guard.requireAdmin()).isEqualTo("petar@example.com");
  }

  @Test
  void requireAdminThrowsWhenEmailNotInList() {
    SecurityContextHolder.getContext().setAuthentication(jwtToken("rando@example.com"));
    PlatformAdminGuard guard =
        new PlatformAdminGuard(new PlatformAdminProperties(List.of("admin@arguslog.org")));
    assertThatThrownBy(guard::requireAdmin)
        .isInstanceOf(AdminAccessDeniedException.class)
        .hasMessageContaining("Not a platform administrator");
  }

  @Test
  void requireAdminRejectsNonJwtAuthentication() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("uuid-not-jwt", "n/a"));
    PlatformAdminGuard guard =
        new PlatformAdminGuard(new PlatformAdminProperties(List.of("admin@arguslog.org")));
    assertThatThrownBy(guard::requireAdmin)
        .isInstanceOf(AdminAccessDeniedException.class)
        .hasMessageContaining("interactive login");
  }

  @Test
  void requireAdminRejectsEmptyAllowlist() {
    SecurityContextHolder.getContext().setAuthentication(jwtToken("admin@arguslog.org"));
    PlatformAdminGuard guard = new PlatformAdminGuard(new PlatformAdminProperties(List.of()));
    assertThatThrownBy(guard::requireAdmin).isInstanceOf(AdminAccessDeniedException.class);
  }

  @Test
  void isCurrentUserAdminReturnsBooleanWithoutThrowing() {
    SecurityContextHolder.getContext().setAuthentication(jwtToken("rando@example.com"));
    PlatformAdminGuard guard =
        new PlatformAdminGuard(new PlatformAdminProperties(List.of("admin@arguslog.org")));
    assertThat(guard.isCurrentUserAdmin()).isFalse();

    SecurityContextHolder.getContext().setAuthentication(jwtToken("admin@arguslog.org"));
    assertThat(guard.isCurrentUserAdmin()).isTrue();
  }

  private static JwtAuthenticationToken jwtToken(String email) {
    Jwt jwt =
        new Jwt(
            "test-token",
            null,
            null,
            Map.of("alg", "none"),
            Map.of("sub", "11111111-1111-1111-1111-111111111111", "email", email));
    return new JwtAuthenticationToken(jwt, List.of(), "11111111-1111-1111-1111-111111111111");
  }
}
