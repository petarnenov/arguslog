package org.arguslog.worker.adapter.out.r2;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.arguslog.worker.application.port.SourceMapStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Reads a {@code .map} object from R2 / MinIO. Returns {@link Optional#empty()} when the key is
 * missing OR any other failure occurs — the caller falls back to un-symbolicated frames either way,
 * and we explicitly do not want a transient R2 hiccup to fail the parent event persist.
 */
@Component
public class S3SourceMapStore implements SourceMapStore {

  private static final Logger log = LoggerFactory.getLogger(S3SourceMapStore.class);

  private final S3Client client;
  private final String bucket;

  public S3SourceMapStore(S3Client client, R2Properties props) {
    this.client = client;
    this.bucket = props.bucket();
  }

  @Override
  public Optional<String> fetch(String r2Key) {
    try {
      ResponseBytes<GetObjectResponse> bytes =
          client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(r2Key).build());
      return Optional.of(new String(bytes.asByteArray(), StandardCharsets.UTF_8));
    } catch (NoSuchKeyException e) {
      log.warn("sourcemap missing in R2: {}", r2Key);
      return Optional.empty();
    } catch (RuntimeException e) {
      log.warn("sourcemap fetch failed for {}: {}", r2Key, e.getMessage());
      return Optional.empty();
    }
  }
}
