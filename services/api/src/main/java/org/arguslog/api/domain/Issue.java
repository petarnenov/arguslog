package org.arguslog.api.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row from the issues table — what the dashboard list view renders. Field set is the public
 * shape; internal stats (e.g. {@code environment_id}) live in dedicated views.
 */
public record Issue(
    long id,
    long projectId,
    String fingerprint,
    Status status,
    Level level,
    String title,
    String culprit,
    Instant firstSeenAt,
    Instant lastSeenAt,
    long occurrenceCount,
    UUID assigneeUserId) {

  public enum Status {
    UNRESOLVED,
    RESOLVED,
    IGNORED;

    public String dbValue() {
      return name().toLowerCase();
    }

    public static Status fromString(String value) {
      for (Status s : values()) {
        if (s.dbValue().equalsIgnoreCase(value)) {
          return s;
        }
      }
      throw new IllegalArgumentException("unknown issue status: " + value);
    }
  }

  public enum Level {
    FATAL,
    ERROR,
    WARNING,
    INFO,
    DEBUG;

    public String dbValue() {
      return name().toLowerCase();
    }

    public static Level fromString(String value) {
      for (Level l : values()) {
        if (l.dbValue().equalsIgnoreCase(value)) {
          return l;
        }
      }
      throw new IllegalArgumentException("unknown event level: " + value);
    }
  }
}
