package org.arguslog.api.releases.application.port;

import java.net.URI;
import java.time.Duration;

/**
 * Outbound port for object-storage operations needed by the api. Right now we only mint presigned
 * PUT URLs so the CLI can stream sourcemap bytes straight to R2 without burning api bandwidth. The
 * worker reads bytes via its own S3 client (separate path).
 */
public interface SourceMapStorage {

  URI presignPut(String r2Key, long expectedSizeBytes, Duration ttl);

  /**
   * Best-effort blob removal. Implementations should swallow "key already gone" and any transient
   * S3 errors — the api row is the source of truth, and a stranded blob is cheap. The boolean
   * return is informational ({@code true} when the SDK call returned without an error).
   */
  boolean deleteObject(String r2Key);
}
