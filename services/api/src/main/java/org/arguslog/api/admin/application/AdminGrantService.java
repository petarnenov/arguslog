package org.arguslog.api.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.arguslog.api.admin.application.port.AdminQueryPort;
import org.arguslog.billing.PlanTier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-only operation: elevate a user's tier (silver / gold / platinum) for a fixed window or
 * permanently. Updates {@code users.tier} so all existing cap / quota enforcement (events,
 * projects, members, retention) automatically respects the new tier; the {@code tier_*} columns
 * carry the grant metadata for the dashboard banner. Always writes an entry into {@code
 * admin_audit_log} so the action is forensically traceable.
 *
 * <p>OSS conversion (V30+): grants are per-user. The legacy per-org grant path is gone — a user's
 * tier covers every org they own automatically.
 */
@Service
public class AdminGrantService {

  /**
   * Months accepted from the API. Zero means a permanent grant (no {@code tier_expires_at} set);
   * positive values write a future expiry which the worker downgrades back to regular on lapse.
   */
  private static final int[] ALLOWED_MONTHS = {0, 1, 3, 6, 12};

  private final AdminQueryPort port;
  private final ObjectMapper json;

  public AdminGrantService(AdminQueryPort port, ObjectMapper json) {
    this.port = port;
    this.json = json;
  }

  /**
   * Per-user grant — elevates {@code targetUserId} to {@code tier} for {@code months} (0 = no
   * expiry). The new tier covers every org the user owns automatically (per-user billing model).
   */
  @Transactional
  public void grantToUser(
      UUID targetUserId,
      String tierRaw,
      int months,
      String reason,
      UUID adminUser,
      String adminEmail) {
    PlanTier tier = parseTier(tierRaw);
    if (tier == PlanTier.REGULAR) {
      throw new IllegalArgumentException(
          "Tier grants only apply to elevated tiers (silver / gold / platinum). Use revokeUser() to drop to regular.");
    }
    requireValidMonths(months);
    Instant until = months == 0 ? null : Instant.now().plus(Duration.ofDays(months * 30L));
    port.recordUserGrant(targetUserId, tier.dbValue(), until, adminUser, reason);
    audit(
        adminUser,
        adminEmail,
        "grant_tier",
        "user",
        targetUserId.toString(),
        Map.of(
            "tier",
            tier.dbValue(),
            "months",
            months,
            "until",
            until == null ? "" : until.toString(),
            "reason",
            reason == null ? "" : reason));
  }

  @Transactional
  public void revokeUser(UUID targetUserId, UUID adminUser, String adminEmail) {
    port.revokeUserGrant(targetUserId);
    audit(adminUser, adminEmail, "revoke_tier", "user", targetUserId.toString(), Map.of());
  }

  private void audit(
      UUID adminUser,
      String adminEmail,
      String action,
      String targetType,
      String targetId,
      Map<String, ?> payload) {
    String body;
    try {
      body = json.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      body = "{}";
    }
    port.writeAudit(adminUser, adminEmail, action, targetType, targetId, body);
  }

  private static PlanTier parseTier(String raw) {
    if (raw == null) throw new IllegalArgumentException("tier is required");
    try {
      return PlanTier.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown tier '" + raw + "'. Allowed: silver, gold, platinum.");
    }
  }

  private static void requireValidMonths(int months) {
    for (int allowed : ALLOWED_MONTHS) {
      if (allowed == months) return;
    }
    throw new IllegalArgumentException("months must be 0 (permanent), 1, 3, 6, or 12");
  }
}
