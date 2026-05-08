package org.arguslog.api.application.port;

import java.util.UUID;

/**
 * Write-side port for org_members. Kept separate from {@link MembershipRepository} so read-only
 * call sites (access guards, listings) don't accidentally pick up mutating capabilities through
 * dependency injection.
 */
public interface MembershipWriteRepository {

  /**
   * Idempotent INSERT keyed on (org_id, user_id). Returns {@code true} when a new row was created,
   * {@code false} if the membership already existed.
   */
  boolean addMember(long orgId, UUID userId, String role);

  /** Updates the role of an existing membership. Returns {@code true} if a row was changed. */
  boolean updateRole(long orgId, UUID userId, String role);

  /** Removes a membership. Returns {@code true} if a row was deleted. */
  boolean removeMember(long orgId, UUID userId);
}
