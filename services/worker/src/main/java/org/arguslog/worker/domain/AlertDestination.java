package org.arguslog.worker.domain;

/**
 * Destination row already-decrypted for dispatch. {@code configJson} holds kind-specific bits
 * (Telegram chat id, Slack webhook url, …); each dispatcher parses what it needs.
 *
 * <p>{@code enabled} mirrors the API-side {@code alert_destinations.enabled} column (V40). The
 * dispatcher already filters at the SQL level, but the field is here too so a future caller that
 * holds an in-memory row can short-circuit cheaply.
 */
public record AlertDestination(
    long id, long orgId, Kind kind, String name, String configJson, boolean enabled) {

  /**
   * Backfill constructor for sites that predate the {@code enabled} toggle (V40). Defaults to
   * {@code enabled = true}.
   */
  public AlertDestination(long id, long orgId, Kind kind, String name, String configJson) {
    this(id, orgId, kind, name, configJson, true);
  }

  public enum Kind {
    TELEGRAM,
    EMAIL,
    SLACK,
    WEBHOOK,
    GITHUB_ISSUE
  }
}
