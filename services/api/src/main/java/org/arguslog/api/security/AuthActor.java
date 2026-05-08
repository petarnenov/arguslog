package org.arguslog.api.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Single source of truth for "who is the actor on this request?". Reads from {@link
 * SecurityContextHolder} so JWT auth (from Keycloak) and PAT auth (from {@code
 * PatAuthenticationFilter}) are interchangeable — every authenticated principal exposes the user
 * UUID via {@code Authentication.getName()}.
 *
 * <p>Use this in controllers instead of binding {@code JwtAuthenticationToken} as a method
 * parameter — that pattern silently breaks PAT-driven calls because Spring can't inject a JWT
 * principal when the request was authenticated through the PAT filter.
 */
public final class AuthActor {

  private AuthActor() {}

  /**
   * UUID of the current authenticated user. Throws {@link IllegalStateException} if the request
   * reached this point without an authenticated principal — indicates a misconfigured filter chain,
   * not a client error.
   */
  public static UUID currentUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      throw new IllegalStateException(
          "AuthActor.currentUserId() called without an authenticated principal");
    }
    try {
      return UUID.fromString(auth.getName());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "Authentication name is not a UUID — Keycloak realm or PAT filter misconfigured?", e);
    }
  }
}
