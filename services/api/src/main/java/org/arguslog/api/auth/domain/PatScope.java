package org.arguslog.api.auth.domain;

import java.util.Locale;
import java.util.Set;

/**
 * Capability granted to a PAT. The wire string ({@code releases:write}, {@code alerts:read}, …) is
 * what gets stored in the {@code personal_access_tokens.scopes} array column AND used as the Spring
 * Security authority name (prefixed with {@code SCOPE_}).
 *
 * <p>A token with a {@code null} scope set in the DB grants every scope — the implicit-all contract
 * from before this column existed.
 *
 * <p>Adding a new scope: add the enum constant, decide which endpoint annotates with
 * {@code @PreAuthorize("hasAuthority('SCOPE_x:y')")}, and bump the UI checkbox list. JWT-issued
 * sessions get every scope automatically (the dashboard user is the implicit owner).
 */
public enum PatScope {
  ORGS_READ("orgs:read"),
  ORGS_WRITE("orgs:write"),
  PROJECTS_READ("projects:read"),
  PROJECTS_WRITE("projects:write"),
  ISSUES_READ("issues:read"),
  EVENTS_READ("events:read"),
  RELEASES_READ("releases:read"),
  RELEASES_WRITE("releases:write"),
  SOURCEMAPS_WRITE("sourcemaps:write"),
  ALERTS_READ("alerts:read"),
  ALERTS_WRITE("alerts:write");

  private final String wire;

  PatScope(String wire) {
    this.wire = wire;
  }

  /** The string stored in the DB column and used as the Spring Security authority suffix. */
  public String wire() {
    return wire;
  }

  /**
   * {@code SCOPE_releases:write} — the form Spring Security expects on a {@code GrantedAuthority}.
   */
  public String authority() {
    return "SCOPE_" + wire;
  }

  /** Returns the scope for the given wire string, or throws if unknown. */
  public static PatScope fromWire(String raw) {
    if (raw == null) throw new IllegalArgumentException("scope is null");
    String lower = raw.trim().toLowerCase(Locale.ROOT);
    for (PatScope s : values()) {
      if (s.wire.equals(lower)) return s;
    }
    throw new IllegalArgumentException("unknown scope: " + raw);
  }

  /** All scopes, used when a token row has {@code scopes IS NULL} (implicit-all). */
  public static Set<PatScope> all() {
    return Set.of(values());
  }
}
