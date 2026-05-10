package org.arguslog.api.auth.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
import org.arguslog.api.admin.PlatformAdminGuard;
import org.arguslog.api.security.AuthActor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me} — minimal "who am I" payload the dashboard fetches once on load. Lets the
 * frontend decide whether to render the Admin nav link without first hammering the admin endpoints
 * and seeing 403s.
 */
@RestController
@RequestMapping(value = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
public class MeController {

  private final PlatformAdminGuard adminGuard;

  public MeController(PlatformAdminGuard adminGuard) {
    this.adminGuard = adminGuard;
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
    return new MeResponse(userId, email, name, adminGuard.isCurrentUserAdmin());
  }

  public record MeResponse(
      @JsonProperty("userId") UUID userId,
      @JsonProperty("email") String email,
      @JsonProperty("displayName") String displayName,
      @JsonProperty("isPlatformAdmin") boolean isPlatformAdmin) {}
}
