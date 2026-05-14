package org.arguslog.api.releases.domain;

import java.time.Instant;

/**
 * Operator-supplied fields for create / update. {@code version} is required; everything else is
 * optional metadata. Service-layer validation lives in {@code ReleaseService.requireVersion} +
 * {@code validateMetadata} so this stays a plain data carrier.
 */
public record ReleaseInput(
    String version,
    Instant releasedAt,
    String gitSha,
    String gitRef,
    String deployStage,
    String changelog) {

  /** Convenience for legacy call-sites (CLI v1, MCP curated tool) that only set the version. */
  public static ReleaseInput versionOnly(String version) {
    return new ReleaseInput(version, null, null, null, null, null);
  }
}
