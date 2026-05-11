package org.arguslog.api.auth.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;
import org.arguslog.api.admin.PlatformAdminGuard;
import org.arguslog.api.billing.application.port.OrgPlanRepository.BonusSnapshot;
import org.arguslog.api.billing.application.port.UserBillingRepository;
import org.arguslog.api.billing.domain.PlanTier;
import org.arguslog.api.security.AuthActor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me} — "who am I" payload the dashboard fetches once on load. Carries the
 * platform-admin flag (so the frontend can show the Admin nav without first hammering admin
 * endpoints) and the user's billing identity (plan, renew, bonus, grace) — per-user since V26,
 * so this is the right surface to expose them.
 */
@RestController
@RequestMapping(value = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
public class MeController {

  private final PlatformAdminGuard adminGuard;
  private final UserBillingRepository userBilling;

  public MeController(PlatformAdminGuard adminGuard, UserBillingRepository userBilling) {
    this.adminGuard = adminGuard;
    this.userBilling = userBilling;
  }

  @GetMapping
  public MeResponse me(Authentication auth) {
    UUID userId = AuthActor.currentUserId();
    String email = null;
    String name = null;
    if (auth instanceof JwtAuthenticationToken jwtAuth) {
      Jwt jwt = jwtAuth.getToken();
      email = jwt.getClaimAsString("email");
      name = jwt.getClaimAsString("name");
      if (name == null || name.isBlank()) name = jwt.getClaimAsString("preferred_username");
    }
    String plan = userBilling.findPlan(userId).orElse(PlanTier.FREE).dbValue();
    Instant renewsAt = userBilling.findRenewsAt(userId).orElse(null);
    Instant graceUntil = userBilling.findPaymentGraceUntil(userId).orElse(null);
    BonusSnapshot bonus = userBilling.findActiveBonus(userId).orElse(null);
    return new MeResponse(
        userId,
        email,
        name,
        adminGuard.isCurrentUserAdmin(),
        plan,
        renewsAt,
        graceUntil,
        bonus == null ? null : bonus.until(),
        bonus == null ? null : bonus.reason());
  }

  public record MeResponse(
      @JsonProperty("userId") UUID userId,
      @JsonProperty("email") String email,
      @JsonProperty("displayName") String displayName,
      @JsonProperty("isPlatformAdmin") boolean isPlatformAdmin,
      @JsonProperty("plan") String plan,
      @JsonProperty("planRenewsAt") Instant planRenewsAt,
      @JsonProperty("paymentGraceUntil") Instant paymentGraceUntil,
      @JsonProperty("bonusUntil") Instant bonusUntil,
      @JsonProperty("bonusReason") String bonusReason) {}
}
