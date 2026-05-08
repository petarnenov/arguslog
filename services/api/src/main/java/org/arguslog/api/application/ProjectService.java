package org.arguslog.api.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.domain.Project;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService implements ProjectUseCase {

  static final int MIN_NAME = 2;
  static final int MAX_NAME = 100;

  /**
   * Closed list of platform identifiers. Mirrors the onboarding form. Server enforces so a stale UI
   * can't sneak unknown values into the DB and break SDK selection logic later.
   */
  static final Set<String> PLATFORMS = Set.of("javascript", "react", "react-native", "java-spring");

  /** Roles that may archive a project (owner can also delete the org wholesale). */
  private static final Set<String> ARCHIVE_ROLES = Set.of("owner", "admin");

  private final ProjectWriteRepository projects;
  private final MembershipRepository memberships;

  public ProjectService(ProjectWriteRepository projects, MembershipRepository memberships) {
    this.projects = projects;
    this.memberships = memberships;
  }

  @Override
  @Transactional
  public Project create(long orgId, String name, String platform) {
    requireName(name);
    requirePlatform(platform);
    return projects.create(orgId, OrgService.slugify(name), name.trim(), platform);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Project> list(long orgId) {
    return projects.listForOrg(orgId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Project> get(long orgId, long projectId) {
    return projects.find(orgId, projectId);
  }

  @Override
  @Transactional
  public boolean archive(UUID actorId, long orgId, long projectId) {
    String role =
        memberships
            .userRoleInOrg(actorId, orgId)
            .orElseThrow(
                () ->
                    new ProjectAccessDeniedException("You are not a member of this organization."));
    if (!ARCHIVE_ROLES.contains(role)) {
      throw new ProjectAccessDeniedException("Only org owners and admins can archive projects.");
    }
    return projects.archive(orgId, projectId);
  }

  private static void requireName(String name) {
    if (name == null) {
      throw new InvalidProjectException("name is required");
    }
    String trimmed = name.trim();
    if (trimmed.length() < MIN_NAME) {
      throw new InvalidProjectException("name must be at least " + MIN_NAME + " characters");
    }
    if (trimmed.length() > MAX_NAME) {
      throw new InvalidProjectException("name must be at most " + MAX_NAME + " characters");
    }
  }

  private static void requirePlatform(String platform) {
    if (platform == null || !PLATFORMS.contains(platform)) {
      throw new InvalidProjectException("platform must be one of " + PLATFORMS);
    }
  }
}
