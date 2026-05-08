package org.arguslog.api.releases.application.port;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.releases.domain.Release;

/**
 * Persistence port for releases. Org isolation is enforced by RLS — the caller is expected to have
 * pinned {@code arguslog.org_id} via {@code OrgContext} before invoking these methods.
 */
public interface ReleaseRepository {

  Release create(long projectId, String version);

  List<Release> listForProject(long projectId);

  Optional<Release> find(long projectId, long id);

  Optional<Release> findByVersion(long projectId, String version);

  /**
   * Updates the {@code version} of a release. Returns the new row, or empty if no row was affected
   * (release not found in this project).
   */
  Optional<Release> updateVersion(long projectId, long id, String newVersion);

  /** Returns {@code true} if a row was deleted, {@code false} if release was not in the project. */
  boolean delete(long projectId, long id);
}
