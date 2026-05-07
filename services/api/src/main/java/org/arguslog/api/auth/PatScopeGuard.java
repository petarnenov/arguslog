package org.arguslog.api.auth;

import org.arguslog.api.auth.adapter.in.web.PatAuthenticationFilter.PatAuthentication;
import org.arguslog.api.auth.domain.PatScope;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Inline scope check for endpoints that mutate something a PAT-only client should be allowed to do
 * explicitly (releases:write, sourcemaps:write, …).
 *
 * <p>JWT-issued sessions (the dashboard) are the implicit owner — they pass without a scope check.
 * PAT auth is gated: the token must carry the required scope or have null scopes (the implicit-all
 * contract for tokens minted before {@code scopes} existed).
 *
 * <p>Inline rather than {@code @PreAuthorize} because we don't already have method security enabled
 * and gating only two endpoints isn't worth the global wiring + every-controller-test authority
 * churn.
 */
public final class PatScopeGuard {

  private PatScopeGuard() {}

  public static void require(PatScope required) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof PatAuthentication pat
        && !pat.token().effectiveScopes().contains(required)) {
      throw new AccessDeniedException("PAT missing required scope: " + required.wire());
    }
  }
}
