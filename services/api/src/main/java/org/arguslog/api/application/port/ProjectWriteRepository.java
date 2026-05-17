package org.arguslog.api.application.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.arguslog.api.application.dto.ProjectStats;
import org.arguslog.api.domain.GitProvider;
import org.arguslog.api.domain.Project;

/** Write/read port for projects. RLS-pinned: the org_id GUC must be set before each call. */
public interface ProjectWriteRepository {

  /**
   * Inserts a new project under {@code orgId} with a unique slug derived from {@code baseSlug}.
   * {@code gitProvider} + {@code gitRepo} are optional but must be both null or both non-null —
   * callers are expected to have validated that pair already (the DB CHECK constraint is a
   * safety net, not the primary gate).
   */
  Project create(
      long orgId,
      String baseSlug,
      String name,
      String platform,
      GitProvider gitProvider,
      String gitRepo);

  List<Project> listForOrg(long orgId);

  Optional<Project> find(long orgId, long projectId);

  /** Soft-archives a live project. Returns {@code false} if it was already archived or absent. */
  boolean archive(long orgId, long projectId);

  /**
   * Updates the display name of a live project. Slug is preserved. Returns the refreshed project,
   * or empty if it does not exist or is already archived.
   */
  Optional<Project> rename(long orgId, long projectId, String name);

  /**
   * Sets or clears the Git repo reference on a live project. Pass both {@code provider} and
   * {@code repo} non-null to link, or both null to clear. Returns the refreshed project, or empty
   * if it does not exist or is already archived.
   */
  Optional<Project> updateGitRepo(
      long orgId, long projectId, GitProvider provider, String repo);

  /**
   * Returns per-project activity stats for every live project in the org, keyed by project id.
   * Projects with no events / issues yet land in the map with zero counts and {@code null} {@code
   * lastEventAt}. Used by the dashboard project-list card; not for hot ingest paths.
   */
  Map<Long, ProjectStats> statsForOrg(long orgId);
}
