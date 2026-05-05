package org.arguslog.api.application.port;

import java.util.UUID;

/** Write-side port for the {@code users} mirror table. Keycloak is the source of truth. */
public interface UserRepository {

  /**
   * Idempotent UPSERT keyed on the Keycloak {@code sub}. Email and display name come from JWT
   * claims and are refreshed on each call so a profile change in Keycloak propagates without a
   * separate sync job.
   */
  void upsertFromJwt(UUID id, String email, String displayName);
}
