package org.arguslog.worker.adapter.out.r2;

import java.net.URI;
import org.arguslog.storage.R2Properties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Builds the {@link S3Client} the symbolicator uses to fetch {@code .map} bytes. Worker does not
 * need {@code S3Presigner} — only the api mints upload URLs.
 */
@Configuration
@EnableConfigurationProperties(R2Properties.class)
public class R2Config {

  // R2 / MinIO want path-style buckets; AWS SDK defaults to virtual-host which breaks here.
  private static final S3Configuration S3_CONFIG =
      S3Configuration.builder().pathStyleAccessEnabled(true).build();

  // R2 ignores region but the SDK still requires one. "auto" matches Cloudflare's docs.
  private static final Region R2_REGION = Region.of("auto");

  @Bean
  public S3Client s3Client(R2Properties props) {
    return S3Client.builder()
        .endpointOverride(URI.create(props.endpoint()))
        .region(R2_REGION)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
        .serviceConfiguration(S3_CONFIG)
        .build();
  }
}
