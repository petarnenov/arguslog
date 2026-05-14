package org.arguslog.api.releases.domain;

import java.time.Instant;

/**
 * One row from {@code releases}. A release tags a CLI-uploaded version that sourcemap artifacts
 * attach to — the worker's symbolication pipeline (P3 #10) joins events back to the matching
 * release before decoding minified frames.
 *
 * <p>Metadata fields ({@code releasedAt}, {@code gitSha}, {@code gitRef}, {@code deployStage},
 * {@code changelog}) are all nullable. {@code createdAt} is the row-insert time and is always
 * populated; {@code releasedAt} is the operator-declared deploy moment and only set when the
 * caller passes it on create/update. The 4-arg convenience constructor exists so older tests
 * and call-sites that don't care about the metadata don't need to thread nulls.
 */
public record Release(
    long id,
    long projectId,
    String version,
    Instant createdAt,
    Instant releasedAt,
    String gitSha,
    String gitRef,
    String deployStage,
    String changelog) {

  public Release(long id, long projectId, String version, Instant createdAt) {
    this(id, projectId, version, createdAt, null, null, null, null, null);
  }
}
