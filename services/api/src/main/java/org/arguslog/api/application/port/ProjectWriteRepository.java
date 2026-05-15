package org.arguslog.api.application.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.arguslog.api.application.dto.ProjectStats;
import org.arguslog.api.domain.Project;

/** Write/read port for projects. RLS-pinned: the org_id GUC must be set before each call. */
public interface ProjectWriteRepository {

  /** Inserts a new project under {@code orgId} with a unique slug derived from {@code baseSlug}. */
  Project create(long orgId, String baseSlug, String name, String platform);

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
   * Returns per-project activity stats for every live project in the org, keyed by project id.
   * Projects with no events / issues yet land in the map with zero counts and {@code null}
   * {@code lastEventAt}. Used by the dashboard project-list card; not for hot ingest paths.
   */
  Map<Long, ProjectStats> statsForOrg(long orgId);
}
