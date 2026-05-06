package org.arguslog.api.releases.domain;

import java.time.Instant;

/**
 * One row from {@code releases}. A release tags a CLI-uploaded version that sourcemap artifacts
 * attach to — the worker's symbolication pipeline (P3 #10) joins events back to the matching
 * release before decoding minified frames.
 */
public record Release(long id, long projectId, String version, Instant createdAt) {}
