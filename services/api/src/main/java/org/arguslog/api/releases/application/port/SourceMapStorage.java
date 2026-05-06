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
}
