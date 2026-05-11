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
 * Admin-only operation: comp a paid plan to an org for a fixed window. Updates
 * {@code organizations.plan} so all existing cap / quota enforcement (events, projects, members,
 * retention) automatically respects the bonus tier; the {@code bonus_*} columns are pure metadata
 * for the dashboard banner. Always writes an entry into {@code admin_audit_log} so the action is
 * forensically traceable.
 */
@Service
public class AdminGrantService {

  /** Months actually accepted from the API — mirrors the regular billing ladder. */
  private static final int[] ALLOWED_MONTHS = {1, 3, 6, 12};

  private final AdminQueryPort port;
  private final ObjectMapper json;

  public AdminGrantService(AdminQueryPort port, ObjectMapper json) {
    this.port = port;
    this.json = json;
  }

  @Transactional
  public void grant(
      long orgId,
      String tierRaw,
      int months,
      String reason,
      UUID adminUser,
      String adminEmail) {
    PlanTier tier = parseTier(tierRaw);
    if (!tier.isPaid()) {
      throw new IllegalArgumentException(
          "Bonus grants only apply to paid tiers (starter / pro / business). Use revoke() to drop to free.");
    }
    requireValidMonths(months);
    Instant until = Instant.now().plus(Duration.ofDays(months * 30L));
    port.recordGrant(orgId, tier.dbValue(), until, adminUser, reason);
    audit(
        adminUser,
        adminEmail,
        "grant_plan",
        "org",
        String.valueOf(orgId),
        Map.of(
            "tier", tier.dbValue(),
            "months", months,
            "until", until.toString(),
            "reason", reason == null ? "" : reason));
  }

  @Transactional
  public void revoke(long orgId, UUID adminUser, String adminEmail) {
    port.revokeGrant(orgId);
    audit(adminUser, adminEmail, "revoke_grant", "org", String.valueOf(orgId), Map.of());
  }

  /**
   * Per-user grant — the V26+ direct path. Granting at the user level avoids the legacy "which
   * org gets the bonus" ambiguity for users with multiple owned orgs; the bonus tier now covers
   * every org the user owns automatically (per-user billing model).
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
    if (!tier.isPaid()) {
      throw new IllegalArgumentException(
          "Bonus grants only apply to paid tiers (starter / pro / business). Use revokeUser() to drop to free.");
    }
    requireValidMonths(months);
    Instant until = Instant.now().plus(Duration.ofDays(months * 30L));
    port.recordUserGrant(targetUserId, tier.dbValue(), until, adminUser, reason);
    audit(
        adminUser,
        adminEmail,
        "grant_plan",
        "user",
        targetUserId.toString(),
        Map.of(
            "tier", tier.dbValue(),
            "months", months,
            "until", until.toString(),
            "reason", reason == null ? "" : reason));
  }

  @Transactional
  public void revokeUser(UUID targetUserId, UUID adminUser, String adminEmail) {
    port.revokeUserGrant(targetUserId);
    audit(
        adminUser, adminEmail, "revoke_grant", "user", targetUserId.toString(), Map.of());
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
          "Unknown tier '" + raw + "'. Allowed: starter, pro, business.");
    }
  }

  private static void requireValidMonths(int months) {
    for (int allowed : ALLOWED_MONTHS) {
      if (allowed == months) return;
    }
    throw new IllegalArgumentException("months must be 1, 3, 6, or 12");
  }
}
