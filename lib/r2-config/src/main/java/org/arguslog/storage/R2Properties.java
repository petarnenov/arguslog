package org.arguslog.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * R2 / S3 connection config. {@code endpoint} points at MinIO in dev (path-style buckets) and at
 * Cloudflare R2 in prod (also path-style — R2 uses a per-account endpoint, no virtual-host).
 *
 * <p>Shared between api (presigns uploads) and worker (fetches sourcemaps) so the env namespace
 * {@code arguslog.r2.*} resolves to the same bucket on both sides. Drift here would silently break
 * "sourcemap not found" with no clear error message.
 */
@ConfigurationProperties(prefix = "arguslog.r2")
public record R2Properties(String endpoint, String bucket, String accessKey, String secretKey) {

  public R2Properties {
    if (endpoint == null || endpoint.isBlank()) endpoint = "http://localhost:9000";
    if (bucket == null || bucket.isBlank()) bucket = "arguslog-sourcemaps";
    if (accessKey == null) accessKey = "";
    if (secretKey == null) secretKey = "";
  }
}
