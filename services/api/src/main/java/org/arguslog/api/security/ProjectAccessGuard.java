package org.arguslog.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Pre-handle interceptor for any URL that carries a {@code {projectId}} path variable. Resolves the
 * project's org, verifies the JWT subject is a member, and primes {@link OrgContext} so the
 * persistence layer can pin {@code arguslog.org_id} for RLS. Always clears the context in
 * afterCompletion so a reused worker thread does not leak the previous request's tenant.
 *
 * <p>404 (not 403) is returned for non-members on purpose — Sentry-style: we never confirm that a
 * project exists to anyone outside its organization.
 */
@Component
@Profile("!test")
public class ProjectAccessGuard implements HandlerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(ProjectAccessGuard.class);

  private final ProjectRepository projects;
  private final MembershipRepository memberships;

  public ProjectAccessGuard(ProjectRepository projects, MembershipRepository memberships) {
    this.projects = projects;
    this.memberships = memberships;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    long projectId = extractProjectId(request);
    UUID userId = currentUserId();

    OptionalLong orgId = projects.findOrgIdForProject(projectId);
    if (orgId.isEmpty()) {
      throw AccessException.notFound(projectId);
    }
    if (!memberships.userIsMemberOfOrg(userId, orgId.getAsLong())) {
      log.debug("user {} denied access to project {}", userId, projectId);
      throw AccessException.forbidden(projectId);
    }

    OrgContext.set(orgId.getAsLong());
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    OrgContext.clear();
  }

  private static long extractProjectId(HttpServletRequest request) {
    @SuppressWarnings("unchecked")
    Map<String, String> vars =
        (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    String raw = vars == null ? null : vars.get("projectId");
    if (raw == null) {
      throw new IllegalStateException(
          "ProjectAccessGuard registered for a route without a {projectId} path variable");
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw AccessException.notFound(0); // surface as 404; bogus path
    }
  }

  private static UUID currentUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      throw new IllegalStateException("ProjectAccessGuard reached without an Authentication");
    }
    try {
      return UUID.fromString(auth.getName());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "JWT subject is not a UUID — Keycloak realm misconfigured?", e);
    }
  }
}
