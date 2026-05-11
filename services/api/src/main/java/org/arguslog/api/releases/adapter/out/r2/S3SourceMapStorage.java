package org.arguslog.api.releases.adapter.out.r2;

import org.arguslog.storage.R2Properties;

import java.net.URI;
import java.time.Duration;
import org.arguslog.api.releases.application.port.SourceMapStorage;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Mints presigned PUT URLs targeted at the configured R2 bucket. The CLI uploads the bytes — api
 * never streams the sourcemap.
 */
@Component
public class S3SourceMapStorage implements SourceMapStorage {

  private static final String CONTENT_TYPE = "application/json"; // sourcemaps are JSON.

  private final S3Presigner presigner;
  private final String bucket;

  public S3SourceMapStorage(S3Presigner presigner, R2Properties props) {
    this.presigner = presigner;
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
}
