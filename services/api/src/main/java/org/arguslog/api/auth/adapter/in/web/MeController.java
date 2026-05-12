package org.arguslog.api.auth.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;
import org.arguslog.api.admin.PlatformAdminGuard;
import org.arguslog.api.security.AuthActor;
import org.arguslog.api.tier.application.port.TierLookupRepository;
import org.arguslog.api.tier.application.port.TierLookupRepository.TierGrantSnapshot;
import org.arguslog.billing.PlanTier;
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
 * endpoints) plus the user's current tier and optional admin-grant snapshot. Post-OSS-conversion
 * there is no payment-related data here — tiers are admin-granted, not purchased.
 */
@RestController
@RequestMapping(value = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
public class MeController {

  private final PlatformAdminGuard adminGuard;
  private final TierLookupRepository tiers;

  public MeController(PlatformAdminGuard adminGuard, TierLookupRepository tiers) {
    this.adminGuard = adminGuard;
    this.tiers = tiers;
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
    String tier = tiers.findTierForUser(userId).orElse(PlanTier.REGULAR).dbValue();
    TierGrantSnapshot grant = tiers.findActiveTierGrant(userId).orElse(null);
    return new MeResponse(
        userId,
        email,
        name,
        adminGuard.isCurrentUserAdmin(),
        tier,
        grant == null ? null : grant.expiresAt(),
        grant == null ? null : grant.reason());
  }

  public record MeResponse(
      @JsonProperty("userId") UUID userId,
      @JsonProperty("email") String email,
      @JsonProperty("displayName") String displayName,
      @JsonProperty("isPlatformAdmin") boolean isPlatformAdmin,
      @JsonProperty("tier") String tier,
      @JsonProperty("tierExpiresAt") Instant tierExpiresAt,
      @JsonProperty("tierReason") String tierReason) {}
}
