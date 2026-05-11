package org.arguslog.api.releases.adapter.out.r2;

import org.arguslog.storage.R2Properties;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(R2Properties.class)
public class R2Config {

  // R2 (and MinIO) want path-style buckets; the AWS SDK defaults to virtual-host which
  // breaks against non-AWS endpoints.
  private static final S3Configuration S3_CONFIG =
      S3Configuration.builder().pathStyleAccessEnabled(true).build();

  // R2 ignores the region but the SDK still requires one. "auto" matches Cloudflare's docs.
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

  @Bean
  public S3Presigner s3Presigner(R2Properties props) {
    return S3Presigner.builder()
        .endpointOverride(URI.create(props.endpoint()))
        .region(R2_REGION)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
        .serviceConfiguration(S3_CONFIG)
        .build();
  }
}
