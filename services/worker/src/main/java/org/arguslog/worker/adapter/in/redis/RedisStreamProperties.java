package org.arguslog.worker.adapter.in.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "arguslog.worker")
public record RedisStreamProperties(
    String streamKey,
    String consumerGroup,
    String consumerName,
    int batchSize,
    Duration pollTimeout) {

  public RedisStreamProperties {
    if (streamKey == null || streamKey.isBlank())
      streamKey = "events:incoming";
    if (consumerGroup == null || consumerGroup.isBlank())
      consumerGroup = "worker";
    if (consumerName == null || consumerName.isBlank())
      consumerName = "worker-1";
    if (batchSize <= 0)
      batchSize = 50;
    if (pollTimeout == null || pollTimeout.isZero() || pollTimeout.isNegative()) {
      pollTimeout = Duration.ofMillis(1000);
    }
  }
}
