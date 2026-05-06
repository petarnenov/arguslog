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
}
