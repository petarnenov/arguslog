package org.arguslog.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.arguslog.api.application.port.MembershipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Same shape as {@link ProjectAccessGuard} but for routes that carry {@code {orgId}} directly
 * (alert destinations, future org-settings endpoints). The org-scoped URL means there is no project
 * to resolve from — we go straight to the membership check, then prime {@link OrgContext} for RLS.
 */
@Component
@Profile("!test")
public class OrgAccessGuard implements HandlerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(OrgAccessGuard.class);

  private final MembershipRepository memberships;

  public OrgAccessGuard(MembershipRepository memberships) {
    this.memberships = memberships;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    long orgId = extractOrgId(request);
    UUID userId = currentUserId();

    if (!memberships.userIsMemberOfOrg(userId, orgId)) {
      log.debug("user {} denied access to org {}", userId, orgId);
      // 404 (not 403) — never confirm an org's existence to non-members.
      throw AccessException.notFound(orgId);
    }

    OrgContext.set(orgId);
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    OrgContext.clear();
  }

  private static long extractOrgId(HttpServletRequest request) {
    @SuppressWarnings("unchecked")
    Map<String, String> vars =
        (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    String raw = vars == null ? null : vars.get("orgId");
    if (raw == null) {
      throw new IllegalStateException(
          "OrgAccessGuard registered for a route without an {orgId} path variable");
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw AccessException.notFound(0);
    }
  }

  private static UUID currentUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      throw new IllegalStateException("OrgAccessGuard reached without an Authentication");
    }
    try {
      return UUID.fromString(auth.getName());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "JWT subject is not a UUID — Keycloak realm misconfigured?", e);
    }
  }
}
