package org.arguslog.api.admin;

import java.util.Locale;
import java.util.Optional;
import org.arguslog.api.admin.config.PlatformAdminProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Gate for {@code /api/v1/admin/**}. Reads the JWT email claim, lowercases it, and checks against
 * {@link PlatformAdminProperties#normalizedEmails()}. PAT-driven calls are NOT eligible — admin
 * actions require an interactive (browser-session) login so the audit trail can carry a real
 * person's identity. Throws {@link AdminAccessDeniedException} on miss; the controller maps it to
 * a 403 problem.
 */
@Component
public class PlatformAdminGuard {

  private final PlatformAdminProperties props;

  public PlatformAdminGuard(PlatformAdminProperties props) {
    this.props = props;
  }

  /** Throws if the current request is not from a platform admin. Returns the admin email. */
  public String requireAdmin() {
    String email = currentEmail().orElseThrow(() -> new AdminAccessDeniedException(
        "Admin endpoints require an interactive login (JWT) — PAT auth not supported here."));
    if (!props.normalizedEmails().contains(email)) {
      throw new AdminAccessDeniedException("Not a platform administrator.");
    }
    return email;
  }

  /** Returns true iff the current authenticated user is a platform admin. Never throws. */
  public boolean isCurrentUserAdmin() {
    return currentEmail().map(e -> props.normalizedEmails().contains(e)).orElse(false);
  }

  private static Optional<String> currentEmail() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) return Optional.empty();
    if (!(auth instanceof JwtAuthenticationToken jwtAuth)) return Optional.empty();
    Jwt jwt = jwtAuth.getToken();
    String email = jwt.getClaimAsString("email");
    if (email == null || email.isBlank()) return Optional.empty();
    return Optional.of(email.trim().toLowerCase(Locale.ROOT));
  }

  public static final class AdminAccessDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AdminAccessDeniedException(String message) {
      super(message);
    }
  }
}
