package org.arguslog.api.domain;

import java.util.Optional;

/**
 * Recognised Git hosting providers for the per-project repo link. Kept intentionally short — adding
 * Bitbucket / Gitea later is one new value here plus one new branches-client implementation; the
 * DB CHECK constraint, the wire DTOs, and the OpenAPI snapshot all pick up the new value
 * automatically because they read this enum.
 *
 * <p>{@code dbValue} is what we store in {@code projects.git_provider} — lowercase short slug, so a
 * hand-written SQL query against the table is readable without translation.
 */
public enum GitProvider {
  GITHUB("github"),
  GITLAB("gitlab");

  private final String dbValue;

  GitProvider(String dbValue) {
    this.dbValue = dbValue;
  }

  public String dbValue() {
    return dbValue;
  }

  public static Optional<GitProvider> fromDbValue(String raw) {
    if (raw == null) return Optional.empty();
    for (GitProvider p : values()) {
      if (p.dbValue.equals(raw)) return Optional.of(p);
    }
    return Optional.empty();
  }
}
