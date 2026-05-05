package org.arguslog.api.application.port;

import java.util.UUID;

/** Read-side port for org_members. */
public interface MembershipRepository {
  boolean userIsMemberOfOrg(UUID userId, long orgId);
}
