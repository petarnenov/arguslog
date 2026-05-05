package org.arguslog.api.application.port;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.domain.Project;

/** Write/read port for projects. RLS-pinned: the org_id GUC must be set before each call. */
public interface ProjectWriteRepository {

  /** Inserts a new project under {@code orgId} with a unique slug derived from {@code baseSlug}. */
  Project create(long orgId, String baseSlug, String name, String platform);

  List<Project> listForOrg(long orgId);

  Optional<Project> find(long orgId, long projectId);
}
