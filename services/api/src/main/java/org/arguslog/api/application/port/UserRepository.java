package org.arguslog.api.application.port;

import java.util.Optional;
import java.util.UUID;

/** Write-side port for the {@code users} mirror table. Keycloak is the source of truth. */
public interface UserRepository {

  /**
   * Idempotent UPSERT keyed on the Keycloak {@code sub}. Email and display name come from JWT
   * claims and are refreshed on each call so a profile change in Keycloak propagates without a
   * separate sync job.
   */
  void upsertFromJwt(UUID id, String email, String displayName);

  /** Lookup an existing user by email (case-insensitive — {@code users.email} is CITEXT). */
  Optional<UUID> findIdByEmail(String email);

  /**
   * Pre-creates a placeholder row for an invitee whose Keycloak account hasn't logged in yet.
   * Display name is null until first login. The next {@code upsertFromJwt} call will realign the id
   * to the JWT {@code sub} via the email-match path; V6's ON UPDATE CASCADE carries org_members
   * memberships to the new id.
   */
  UUID createPlaceholder(String email);
}
