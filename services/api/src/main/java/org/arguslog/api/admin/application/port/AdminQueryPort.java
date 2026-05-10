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
 * accidentally reach for "give me everyone" queries — admin views are intentionally privileged
 * and unscoped to a single org / user.
 */
public interface AdminQueryPort {

  AdminStats stats();

  /**
   * Paginated user list. {@code search} is matched as a case-insensitive substring against email
   * + display name; null/blank → no filter.
   */
  List<AdminUserRow> listUsers(String search, int offset, int limit);

  long countUsers(String search);

  List<AdminOrgRow> listOrgs(String search, int offset, int limit);

  long countOrgs(String search);

  Optional<AdminOrgRow> getOrg(long orgId);

  /** Active grant if any (bonus_until in the future), else empty. */
  Optional<BonusGrant> findActiveBonus(long orgId);

  void recordGrant(long orgId, String plan, java.time.Instant until, UUID grantedBy, String reason);

  void revokeGrant(long orgId);

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
