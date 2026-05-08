package org.arguslog.api.application;

import java.util.List;
import java.util.UUID;
import org.arguslog.api.domain.Member;

public interface MemberUseCase {

  /** All members of {@code orgId}. Caller must already be a member (enforced by OrgAccessGuard). */
  List<Member> list(long orgId);

  /**
   * Add {@code email} to {@code orgId} with the given role. Owner-only. Idempotent: re-inviting an
   * existing member with a different role is rejected as a conflict — use {@link #changeRole}
   * instead. Sends a best-effort notification email.
   */
  Member invite(UUID actorId, long orgId, String email, String role);

  /** Owner-only. Demoting / removing the last owner is rejected as {@link LastOwnerException}. */
  Member changeRole(UUID actorId, long orgId, UUID targetUserId, String role);

  /**
   * Remove a member. Owners can remove anyone except the last owner. Non-owners can only remove
   * themselves (leave the org).
   */
  void remove(UUID actorId, long orgId, UUID targetUserId);

  final class InvalidMemberException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidMemberException(String message) {
      super(message);
    }
  }

  /** Caller's role in the org is insufficient for the requested operation. */
  final class MemberAccessDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MemberAccessDeniedException(String message) {
      super(message);
    }
  }

  /** Target user is already a member of this org (with the same or different role). */
  final class DuplicateMemberException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DuplicateMemberException(String message) {
      super(message);
    }
  }

  /** Operation would leave the org without an owner. */
  final class LastOwnerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LastOwnerException(String message) {
      super(message);
    }
  }

  /** Target user is not a member of this org. */
  final class MemberNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MemberNotFoundException(String message) {
      super(message);
    }
  }
}
