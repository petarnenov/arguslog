package org.arguslog.api.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.arguslog.api.application.dto.ProjectStats;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.PlatformRepository;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.domain.GitProvider;
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

  /** Same set governs rename — admins can already archive, so they can rename too. */
  private static final Set<String> RENAME_ROLES = Set.of("owner", "admin");

  /**
   * GitHub: exactly one slash. Owner up to 39 chars (GitHub username rules), repo up to 100 chars.
   * Refuse leading dot/dash so we don't accidentally store path-traversal-shaped junk.
   */
  private static final Pattern GITHUB_REPO_PATTERN =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,38}/[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");

  /**
   * GitLab: at least one slash, supports nested groups ({@code group/sub/project}). Each segment
   * is up to 255 chars per GitLab's path rules; cap depth at 12 segments to keep the row size
   * under the column limit and reject obviously bogus paste shapes.
   */
  private static final Pattern GITLAB_REPO_PATTERN =
      Pattern.compile(
          "^[A-Za-z0-9][A-Za-z0-9._-]{0,254}(/[A-Za-z0-9][A-Za-z0-9._-]{0,254}){1,11}$");

  /** Normalized {@code (provider, repo)} pair, or {@code null} when cleared. */
  public record GitRef(GitProvider provider, String repo) {}

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
  public Project create(
      long orgId, String name, String platform, GitProvider gitProvider, String gitRepo) {
    requireName(name);
    requirePlatform(platform);
    requireProjectCapAvailable(orgId);
    GitRef ref = normalizeGitRef(gitProvider, gitRepo);
    return projects.create(
        orgId,
        OrgService.slugify(name),
        name.trim(),
        platform,
        ref == null ? null : ref.provider(),
        ref == null ? null : ref.repo());
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
  public Map<Long, ProjectStats> statsForOrg(long orgId) {
    return projects.statsForOrg(orgId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Project> get(long orgId, long projectId) {
    return projects.find(orgId, projectId);
  }

  @Override
  @Transactional
  public Optional<Project> rename(UUID actorId, long orgId, long projectId, String name) {
    requireName(name);
    requireRenameRole(actorId, orgId);
    return projects.rename(orgId, projectId, name.trim());
  }

  @Override
  @Transactional
  public Optional<Project> updateGitRepo(
      UUID actorId, long orgId, long projectId, GitProvider provider, String repo) {
    requireRenameRole(actorId, orgId);
    GitRef ref = normalizeGitRef(provider, repo);
    return projects.updateGitRepo(
        orgId, projectId, ref == null ? null : ref.provider(), ref == null ? null : ref.repo());
  }

  private void requireRenameRole(UUID actorId, long orgId) {
    String role =
        memberships
            .userRoleInOrg(actorId, orgId)
            .orElseThrow(
                () ->
                    new ProjectAccessDeniedException(
                        "You are not a member of this organization."));
    if (!RENAME_ROLES.contains(role)) {
      throw new ProjectAccessDeniedException("Only org owners and admins can rename projects.");
    }
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

  /**
   * Validates the {@code (provider, repo)} pair and returns the canonical form, or {@code null}
   * if the caller asked to clear (both null/blank).
   *
   * <p>Accepts paste convenience shapes — full URLs ({@code https://github.com/...},
   * {@code https://gitlab.com/...}), SSH clone strings, and trailing {@code .git} / {@code /} —
   * so users don't have to scrub before submitting. When the {@code repo} field is a URL, the
   * provider is auto-detected from the host; if both a provider hint and a URL are given, they
   * must agree or this throws.
   */
  static GitRef normalizeGitRef(GitProvider providerHint, String rawRepo) {
    boolean repoBlank = rawRepo == null || rawRepo.isBlank();
    if (providerHint == null && repoBlank) return null;
    if (repoBlank) {
      throw new InvalidProjectException("gitRepo is required when gitProvider is set");
    }
    String s = rawRepo.trim();

    GitProvider detected = detectProviderFromPrefix(s);
    GitProvider provider;
    if (detected != null) {
      if (providerHint != null && providerHint != detected) {
        throw new InvalidProjectException(
            "gitRepo URL host is "
                + detected.dbValue()
                + " but gitProvider is "
                + providerHint.dbValue());
      }
      provider = detected;
      s = stripHostPrefix(s, detected);
    } else {
      if (providerHint == null) {
        throw new InvalidProjectException(
            "gitProvider is required (one of: " + GitProvider.GITHUB.dbValue() + ", " + GitProvider.GITLAB.dbValue() + ")");
      }
      provider = providerHint;
    }

    if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
    if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);

    Pattern expected = provider == GitProvider.GITHUB ? GITHUB_REPO_PATTERN : GITLAB_REPO_PATTERN;
    if (!expected.matcher(s).matches()) {
      String example =
          provider == GitProvider.GITHUB ? "acme/widgets" : "acme-group/widgets (or group/sub/project)";
      throw new InvalidProjectException(
          "gitRepo must look like \""
              + example
              + "\" for "
              + provider.dbValue()
              + " — got: "
              + rawRepo);
    }
    return new GitRef(provider, s);
  }

  /**
   * Sniff a known provider out of a copy-pasted URL / clone string. Returns {@code null} if the
   * input looks like a bare {@code owner/repo} (no host) — caller must then rely on the explicit
   * provider hint.
   */
  private static GitProvider detectProviderFromPrefix(String s) {
    if (s.startsWith("git@github.com:")
        || s.startsWith("https://github.com/")
        || s.startsWith("http://github.com/")
        || s.startsWith("github.com/")) {
      return GitProvider.GITHUB;
    }
    if (s.startsWith("git@gitlab.com:")
        || s.startsWith("https://gitlab.com/")
        || s.startsWith("http://gitlab.com/")
        || s.startsWith("gitlab.com/")) {
      return GitProvider.GITLAB;
    }
    return null;
  }

  private static String stripHostPrefix(String s, GitProvider provider) {
    String host = provider == GitProvider.GITHUB ? "github.com" : "gitlab.com";
    if (s.startsWith("git@" + host + ":")) return s.substring(("git@" + host + ":").length());
    if (s.startsWith("https://" + host + "/")) return s.substring(("https://" + host + "/").length());
    if (s.startsWith("http://" + host + "/")) return s.substring(("http://" + host + "/").length());
    if (s.startsWith(host + "/")) return s.substring((host + "/").length());
    return s;
  }
}
