package org.arguslog.api.application.port;

import java.util.OptionalLong;

/** Read-side port for projects. Used by the access guard before it sets OrgContext. */
public interface ProjectRepository {
  OptionalLong findOrgIdForProject(long projectId);
}
