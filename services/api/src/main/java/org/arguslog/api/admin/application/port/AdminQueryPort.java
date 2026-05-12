package org.arguslog.api.admin.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.admin.domain.AdminAuditEntry;
import org.arguslog.api.admin.domain.AdminOrgRow;
import org.arguslog.api.admin.domain.AdminStats;
import org.arguslog.api.admin.domain.AdminUserRow;
import org.arguslog.api.admin.domain.BonusGrant;

/**
 * Read-side port for admin endpoints. Lives in its own package so the regular app layer doesn't
 * accidentally reach for "give me everyone" queries — admin views are intentionally privileged and
 * unscoped to a single org / user. Grant operations live here too (write-side) since they share the
 * admin-only access guard.
 */
public interface AdminQueryPort {

  AdminStats stats();

  List<AdminUserRow> listUsers(String search, int offset, int limit);

  long countUsers(String search);

  List<AdminOrgRow> listOrgs(String search, int offset, int limit);

  long countOrgs(String search);

  Optional<AdminOrgRow> getOrg(long orgId);

  /**
   * Per-user tier grant. {@code until} may be null for a permanent grant; positive values write a
   * {@code tier_expires_at} which the worker downgrades back to regular on lapse.
   */
  void recordUserGrant(
      UUID userId, String tier, java.time.Instant until, UUID grantedBy, String reason);

  /** Revokes a per-user tier grant, dropping the user back to regular. */
  void revokeUserGrant(UUID userId);

  /** Active grant for a user (tier_expires_at in the future), else empty. */
  Optional<BonusGrant> findActiveUserBonus(UUID userId);

  List<AdminAuditEntry> listAudit(int offset, int limit);

  long countAudit();

  void writeAudit(
      UUID adminUser,
      String adminEmail,
      String action,
      String targetType,
      String targetId,
      String payloadJson);
}
