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

  /**
   * Per-user grant — bonus plan applied directly to a user (V26+ source of truth). Updates the
   * org rows of the user's owned orgs too, mirroring the dual-write semantics in {@link
   * #recordGrant(long, String, java.time.Instant, UUID, String)} so old org-level reads stay
   * consistent during the V27 deprecation window.
   */
  void recordUserGrant(
      UUID userId, String plan, java.time.Instant until, UUID grantedBy, String reason);

  /** Per-user revoke — clears the user's bonus + plan back to free, mirrors onto owned orgs. */
  void revokeUserGrant(UUID userId);

  /** Active per-user grant if any (users.bonus_until in the future), else empty. */
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
