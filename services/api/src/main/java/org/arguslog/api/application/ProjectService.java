package org.arguslog.api.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.PlatformRepository;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.domain.Project;
import org.arguslog.api.tier.application.port.TierLookupRepository;
import org.arguslog.billing.PlanTier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService implements ProjectUseCase {

  static final int MIN_NAME = 2;
  static final int MAX_NAME = 100;

  /** Roles that may archive a project (owner can also delete the org wholesale). */
  private static final Set<String> ARCHIVE_ROLES = Set.of("owner", "admin");

  private final ProjectWriteRepository projects;
  private final MembershipRepository memberships;
  private final PlatformRepository platforms;
  private final TierLookupRepository tiers;

  public ProjectService(
      ProjectWriteRepository projects,
      MembershipRepository memberships,
      PlatformRepository platforms,
      TierLookupRepository tiers) {
    this.projects = projects;
    this.memberships = memberships;
    this.platforms = platforms;
    this.tiers = tiers;
  }

  @Override
  @Transactional
  public Project create(long orgId, String name, String platform) {
    requireName(name);
    requirePlatform(platform);
    requireProjectCapAvailable(orgId);
    return projects.create(orgId, OrgService.slugify(name), name.trim(), platform);
  }

  private void requireProjectCapAvailable(long orgId) {
    PlanTier tier = tiers.findTier(orgId).orElse(PlanTier.REGULAR);
    int cap = tier.projectCap();
    if (cap == Integer.MAX_VALUE) return;
    int existing = projects.listForOrg(orgId).size();
    if (existing >= cap) {
      throw new ProjectCapExceededException(
          "Your "
              + tier.dbValue()
              + " tier is limited to "
              + cap
              + " project"
              + (cap == 1 ? "" : "s")
              + ". Upgrade or archive an existing project to add another.");
    }
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

  private void requirePlatform(String platform) {
    Set<String> known = platforms.enabledSlugs();
    if (platform == null || !known.contains(platform)) {
      throw new InvalidProjectException("platform must be one of " + known);
    }
  }
}
