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
    UUID assigneeUserId,
    Long firstSeenReleaseId,
    String firstSeenReleaseVersion,
    String aiAnalysis,
    String aiAnalysisModel,
    Instant aiAnalyzedAt) {

  /**
   * Convenience constructor preserving older test/builder sites that don't set release attribution
   * OR the auto-triage analysis fields. Defaults the trailing five fields to {@code null} — same
   * posture as a freshly-ingested issue before either subsystem has touched it.
   */
  public Issue(
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
    this(
        id,
        projectId,
        fingerprint,
        status,
        level,
        title,
        culprit,
        firstSeenAt,
        lastSeenAt,
        occurrenceCount,
        assigneeUserId,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Convenience constructor for sites that DO know release attribution but predate the auto-triage
   * AI analysis fields. Defaults the three AI fields to {@code null}.
   */
  public Issue(
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
      UUID assigneeUserId,
      Long firstSeenReleaseId,
      String firstSeenReleaseVersion) {
    this(
        id,
        projectId,
        fingerprint,
        status,
        level,
        title,
        culprit,
        firstSeenAt,
        lastSeenAt,
        occurrenceCount,
        assigneeUserId,
        firstSeenReleaseId,
        firstSeenReleaseVersion,
        null,
        null,
        null);
  }

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
