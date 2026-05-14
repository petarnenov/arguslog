package org.arguslog.api.releases.adapter.out.r2;

import java.net.URI;
import java.time.Duration;
import org.arguslog.api.releases.application.port.SourceMapStorage;
import org.arguslog.storage.R2Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Mints presigned PUT URLs targeted at the configured R2 bucket. The CLI uploads the bytes — api
 * never streams the sourcemap. Also handles best-effort blob deletion via the standard SDK client
 * when a release / source-map row is removed.
 */
@Component
public class S3SourceMapStorage implements SourceMapStorage {

  private static final Logger log = LoggerFactory.getLogger(S3SourceMapStorage.class);
  private static final String CONTENT_TYPE = "application/json"; // sourcemaps are JSON.

  private final S3Presigner presigner;
  private final S3Client client;
  private final String bucket;

  public S3SourceMapStorage(S3Presigner presigner, S3Client client, R2Properties props) {
    this.presigner = presigner;
    this.client = client;
    this.bucket = props.bucket();
  }

  @Override
  public URI presignPut(String r2Key, long expectedSizeBytes, Duration ttl) {
    PutObjectRequest objectReq =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(r2Key)
            .contentLength(expectedSizeBytes)
            .contentType(CONTENT_TYPE)
            .build();

    PutObjectPresignRequest presignReq =
        PutObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .putObjectRequest(objectReq)
            .build();

    // url() returns java.net.URL; URI.create on its toString sidesteps URISyntaxException
    // (the SDK already produces a well-formed URL).
    return URI.create(presigner.presignPutObject(presignReq).url().toString());
  }

  @Override
  public boolean deleteObject(String r2Key) {
    try {
      client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(r2Key).build());
      return true;
    } catch (RuntimeException e) {
      // Best-effort: a missing key (e.g. row dropped, blob already purged by lifecycle policy) or
      // a transient SDK error should NOT roll back the database delete. The service layer logs.
      log.warn("r2 delete failed for key {} (bucket {}): {}", r2Key, bucket, e.getMessage());
      return false;
    }
  }
}
