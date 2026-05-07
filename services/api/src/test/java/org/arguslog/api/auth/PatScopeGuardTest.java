package org.arguslog.api.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.arguslog.api.auth.adapter.in.web.PatAuthenticationFilter.PatAuthentication;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.auth.domain.PersonalAccessToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PatScopeGuardTest {

  private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void noAuthenticationIsTreatedAsJwtSession() {
    // Test profile uses permitAll() so SecurityContext can be empty when controllers call require.
    // Empty context must NOT 403 — only a PAT that lacks the scope should.
    SecurityContextHolder.clearContext();
    assertThatCode(() -> PatScopeGuard.require(PatScope.RELEASES_WRITE)).doesNotThrowAnyException();
  }

  @Test
  void jwtAuthenticationBypassesScopeCheck() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "00000000-0000-0000-0000-000000000001",
                "n/a",
                java.util.List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    assertThatCode(() -> PatScopeGuard.require(PatScope.RELEASES_WRITE)).doesNotThrowAnyException();
  }

  @Test
  void patWithRequiredScopePasses() {
    PersonalAccessToken token =
        new PersonalAccessToken(
            7L,
            USER,
            "ci",
            "ABCDEFGH",
            null,
            null,
            Instant.parse("2026-05-05T12:00:00Z"),
            EnumSet.of(PatScope.RELEASES_WRITE));
    SecurityContextHolder.getContext().setAuthentication(new PatAuthentication(token));
    assertThatCode(() -> PatScopeGuard.require(PatScope.RELEASES_WRITE)).doesNotThrowAnyException();
  }

  @Test
  void patWithoutRequiredScopeIsDenied() {
    PersonalAccessToken token =
        new PersonalAccessToken(
            7L,
            USER,
            "ci",
            "ABCDEFGH",
            null,
            null,
            Instant.parse("2026-05-05T12:00:00Z"),
            EnumSet.of(PatScope.ISSUES_READ));
    SecurityContextHolder.getContext().setAuthentication(new PatAuthentication(token));

    assertThatThrownBy(() -> PatScopeGuard.require(PatScope.RELEASES_WRITE))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("releases:write");
  }

  @Test
  void patWithNullScopesIsImplicitAll() {
    // Pre-V12 tokens (scopes IS NULL) must keep working — that's the migration contract.
    PersonalAccessToken token =
        new PersonalAccessToken(
            7L, USER, "ci", "ABCDEFGH", null, null, Instant.parse("2026-05-05T12:00:00Z"), null);
    SecurityContextHolder.getContext().setAuthentication(new PatAuthentication(token));

    assertThatCode(() -> PatScopeGuard.require(PatScope.RELEASES_WRITE)).doesNotThrowAnyException();
    assertThatCode(() -> PatScopeGuard.require(PatScope.SOURCEMAPS_WRITE))
        .doesNotThrowAnyException();
  }
}
