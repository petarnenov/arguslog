package org.arguslog.api.application.port;

import java.util.List;
import java.util.UUID;
import org.arguslog.api.domain.Org;

/** Write-side port for organizations + the membership row created alongside them. */
public interface OrgWriteRepository {

  /**
   * Insert a new org with a unique slug derived from {@code baseSlug}. The slug is appended with
   * {@code -2}, {@code -3}, … on collision until a free one is found.
   */
  Org create(String baseSlug, String name);

  /** Adds {@code userId} as the given role to {@code orgId}. Idempotent on the (org_id, user_id) PK. */
  void addMember(long orgId, UUID userId, String role);

  /** Returns every org {@code userId} is a member of, ordered by org slug ascending. */
  List<Org> listForUser(UUID userId);
}
