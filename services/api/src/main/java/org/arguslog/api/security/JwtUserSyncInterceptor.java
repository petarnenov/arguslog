package org.arguslog.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.arguslog.api.application.port.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Realigns the {@code users} row to the JWT subject on every authenticated request, before
 * downstream guards inspect membership. Two failure modes this prevents:
 *
 * <ol>
 *   <li>Invited user signs in for the first time. {@link
 *       org.arguslog.api.application.MemberService#invite} pre-created a placeholder row keyed by
 *       email with a random UUID; without this hook, the JWT {@code sub} never binds to that row,
 *       so the membership stays orphaned and the user appears stuck on "(invitation pending)".
 *   <li>Keycloak realm reseed rotates the user's {@code sub}. The email-keyed fallback in {@link
 *       org.arguslog.api.adapter.out.postgres.JdbcUserRepository#upsertFromJwt} carries memberships
 *       across via the V6 {@code ON UPDATE CASCADE}.
 * </ol>
 *
 * <p>Skips silently for PAT-authenticated requests — PATs cannot be issued without an existing user
 * row, so there is nothing to sync.
 */
@Component
@Profile("!test")
public class JwtUserSyncInterceptor implements HandlerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(JwtUserSyncInterceptor.class);

  private final UserRepository users;

  public JwtUserSyncInterceptor(UserRepository users) {
    this.users = users;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
      return true;
    }

    Jwt jwt = jwtAuth.getToken();
    String email = jwt.getClaimAsString("email");
    if (email == null || email.isBlank()) {
      // Realm without an email claim — nothing to bind to. Don't fail the request; downstream
      // guards still get to deny it on membership grounds.
      return true;
    }

    String displayName = jwt.getClaimAsString("name");
    if (displayName == null || displayName.isBlank()) {
      displayName = jwt.getClaimAsString("preferred_username");
    }

    UUID sub;
    try {
      sub = UUID.fromString(auth.getName());
    } catch (IllegalArgumentException e) {
      // Same invariant as OrgAccessGuard — a non-UUID sub means the realm is misconfigured.
      throw new IllegalStateException(
          "JWT subject is not a UUID — Keycloak realm misconfigured?", e);
    }

    try {
      users.upsertFromJwt(sub, email, displayName);
    } catch (RuntimeException e) {
      // Never block a request because of a sync hiccup (e.g., a transient DB error). The membership
      // check that runs next will surface the real authorization outcome.
      log.warn("upsertFromJwt failed for sub={} email={}", sub, email, e);
    }
    return true;
  }
}
