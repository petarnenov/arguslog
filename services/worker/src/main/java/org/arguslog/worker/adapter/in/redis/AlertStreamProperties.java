package org.arguslog.worker.adapter.in.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties for the alerts pipeline's listener — separate stream + consumer group from the ingest
 * one ({@link RedisStreamProperties}) so a slow dispatch (Telegram down, etc.) cannot stall event
 * persistence.
 */
@ConfigurationProperties(prefix = "argus.worker.alerts")
public record AlertStreamProperties(
    String streamKey,
    String consumerGroup,
    String consumerName,
    int batchSize,
    Duration pollTimeout) {

  public AlertStreamProperties {
    if (streamKey == null || streamKey.isBlank()) streamKey = "events:persisted";
    if (consumerGroup == null || consumerGroup.isBlank()) consumerGroup = "worker-alerts";
    if (consumerName == null || consumerName.isBlank()) consumerName = "worker-alerts-1";
    if (batchSize <= 0) batchSize = 25;
    if (pollTimeout == null || pollTimeout.isZero() || pollTimeout.isNegative()) {
      pollTimeout = Duration.ofMillis(1000);
    }
  }
}
