package org.arguslog.api.application.port;

import java.util.Optional;
import java.util.UUID;

/** Read-side port for org_members. */
public interface MembershipRepository {
  boolean userIsMemberOfOrg(UUID userId, long orgId);

  /** Role of {@code userId} in {@code orgId}, or empty if not a member. */
  Optional<String> userRoleInOrg(UUID userId, long orgId);
}
