package org.arguslog.worker.domain;

import java.util.Objects;

/**
 * Grouping key for an event, plus the human-facing title/culprit derived alongside it. The hash is
 * what we store + index in {@code issues.fingerprint}; title/culprit are denormalized convenience
 * fields the dashboard renders on the issue list.
 */
public record Fingerprint(String hash, String title, String culprit, Level level) {

  public Fingerprint {
    Objects.requireNonNull(hash, "hash");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(level, "level");
  }

  public enum Level {
    FATAL("fatal"),
    ERROR("error"),
    WARNING("warning"),
    INFO("info"),
    DEBUG("debug");

    private final String dbValue;

    Level(String dbValue) {
      this.dbValue = dbValue;
    }

    public String dbValue() {
      return dbValue;
    }

    public static Level fromString(String value) {
      if (value == null) {
        return ERROR;
      }
      for (Level l : values()) {
        if (l.dbValue.equalsIgnoreCase(value)) {
          return l;
        }
      }
      return ERROR;
    }
  }
}
