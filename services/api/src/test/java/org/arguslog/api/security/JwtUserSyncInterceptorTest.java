package org.arguslog.api.security;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.arguslog.api.application.port.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class JwtUserSyncInterceptorTest {

  @Mock UserRepository users;

  JwtUserSyncInterceptor interceptor;
  UUID sub;

  @BeforeEach
  void setUp() {
    interceptor = new JwtUserSyncInterceptor(users);
    sub = UUID.fromString("00000000-0000-0000-0000-000000000001");
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void upsertsFromJwtClaimsOnEveryRequest() {
    authenticateJwt(sub, Map.of("email", "alice@example.com", "name", "Alice"));

    boolean ok =
        interceptor.preHandle(
            new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

    org.assertj.core.api.Assertions.assertThat(ok).isTrue();
    verify(users).upsertFromJwt(sub, "alice@example.com", "Alice");
  }

  @Test
  void fallsBackToPreferredUsernameWhenNameIsBlank() {
    authenticateJwt(sub, Map.of("email", "bob@example.com", "preferred_username", "bob"));

    interceptor.preHandle(
        new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

    verify(users).upsertFromJwt(sub, "bob@example.com", "bob");
  }

  @Test
  void fallsBackToEmailLocalPartWhenNoNameAndNoPreferredUsername() {
    // GH #39 — Keycloak magic-link sign-in can issue an access token that carries `email`
    // but no `name` and no `preferred_username` claim (e.g. when the client scope config
    // omits `profile`). Without a fallback the upsert wrote display_name=NULL, which the
    // Members UI keys on to render "(invitation pending)" — so the invitee appeared stuck
    // on pending even after they had successfully signed in.
    authenticateJwt(sub, Map.of("email", "petar_nenov@abv.bg"));

    interceptor.preHandle(
        new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

    verify(users).upsertFromJwt(sub, "petar_nenov@abv.bg", "petar_nenov");
  }

  @Test
  void fallsBackToWholeEmailWhenLocalPartIsEmpty() {
    // Pathological "@foo" email — we still must not write a NULL display_name, so degrade
    // to the whole string rather than NPE-ing on substring(0, 0).
    authenticateJwt(sub, Map.of("email", "@weird.example"));

    interceptor.preHandle(
        new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

    verify(users).upsertFromJwt(sub, "@weird.example", "@weird.example");
  }

  @Test
  void skipsWhenAuthIsNotJwt() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(sub.toString(), "n/a", java.util.List.of()));

    interceptor.preHandle(
        new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

    verifyNoInteractions(users);
  }

  @Test
  void skipsWhenNoAuthIsPresent() {
    interceptor.preHandle(
        new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

    verifyNoInteractions(users);
  }

  @Test
  void skipsWhenEmailClaimIsMissing() {
    authenticateJwt(sub, Map.of("name", "Carol"));

    interceptor.preHandle(
        new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

    verify(users, never()).upsertFromJwt(Mockito.any(), Mockito.any(), Mockito.any());
  }

  @Test
  void allowsRequestEvenWhenUpsertThrows() {
    authenticateJwt(sub, Map.of("email", "dave@example.com", "name", "Dave"));
    Mockito.doThrow(new RuntimeException("db down"))
        .when(users)
        .upsertFromJwt(sub, "dave@example.com", "Dave");

    boolean ok =
        interceptor.preHandle(
            new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

    org.assertj.core.api.Assertions.assertThat(ok).isTrue();
  }

  private static void authenticateJwt(UUID sub, Map<String, Object> claims) {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject(sub.toString())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claims(c -> c.putAll(claims))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of(), sub.toString()));
  }
}
