package org.arguslog.worker.domain;

/**
 * Destination row already-decrypted for dispatch. {@code configJson} holds kind-specific bits
 * (Telegram chat id, Slack webhook url, …); each dispatcher parses what it needs.
 */
public record AlertDestination(long id, long orgId, Kind kind, String name, String configJson) {

  public enum Kind {
    TELEGRAM,
    EMAIL,
    SLACK,
    WEBHOOK
  }
}
