package org.arguslog.api.alerts.domain;

import java.time.Instant;

/**
 * Public-facing alert destination row. {@code config} is the decrypted JSON the dispatcher needs
 * (chat ids, webhook URLs, …); it never leaves the api process — the controller layer always scrubs
 * it from the response.
 */
public record AlertDestination(
    long id,
    long orgId,
    DestinationKind kind,
    String name,
    String configJson,
    boolean enabled,
    Instant createdAt) {

  /**
   * Backfill constructor for older test/builder sites that predate the {@code enabled} toggle
   * (V40). Defaults to {@code enabled = true} — same posture as a freshly-created destination
   * straight off the DB default.
   */
  public AlertDestination(
      long id,
      long orgId,
      DestinationKind kind,
      String name,
      String configJson,
      Instant createdAt) {
    this(id, orgId, kind, name, configJson, true, createdAt);
  }
}
