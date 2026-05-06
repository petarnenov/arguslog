package org.arguslog.worker.adapter.out.r2;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker-side R2 / S3 connection config — same {@code arguslog.r2.*} namespace as the api so a
 * single env block configures both. Worker only fetches sourcemaps; presigning lives in the api.
 * TODO(P4): extract this and the api copy to a shared module — drift between the two would cause
 * silent "sourcemap not found" failures that look like missing uploads.
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
