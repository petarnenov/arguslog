package org.arguslog.worker.adapter.in.redis;

import jakarta.annotation.PostConstruct;
import org.arguslog.worker.application.ProcessEventUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.ConsumerStreamReadRequest;

@Configuration
@EnableConfigurationProperties(RedisStreamProperties.class)
@ConditionalOnProperty(name = "arguslog.worker.stream-enabled", matchIfMissing = true)
public class RedisStreamConfig {

  private static final Logger log = LoggerFactory.getLogger(RedisStreamConfig.class);

  private final RedisStreamProperties props;
  private final StringRedisTemplate redis;

  public RedisStreamConfig(RedisStreamProperties props, StringRedisTemplate redis) {
    this.props = props;
    this.redis = redis;
  }

  /**
   * XGROUP CREATE on startup. {@code MKSTREAM} so the worker can boot before ingest has written its
   * first record. BUSYGROUP just means the group already exists — fine.
   */
  @PostConstruct
  void ensureConsumerGroup() {
    try {
      redis
          .opsForStream()
          .createGroup(props.streamKey(), ReadOffset.from("0"), props.consumerGroup());
      log.info("created consumer group {} on stream {}", props.consumerGroup(), props.streamKey());
    } catch (Exception e) {
      if (isBusyGroup(e)) {
        log.debug(
            "consumer group {} on stream {} already exists",
            props.consumerGroup(),
            props.streamKey());
      } else {
        throw e;
      }
    }
  }

  private static boolean isBusyGroup(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      String msg = c.getMessage();
      if (msg != null && msg.contains("BUSYGROUP")) {
        return true;
      }
    }
    return false;
  }

  @Bean
  public RedisStreamEventListener redisStreamEventListener(ProcessEventUseCase useCase) {
    return new RedisStreamEventListener(useCase, redis, props);
  }

  @Bean(initMethod = "start", destroyMethod = "stop")
  public StreamMessageListenerContainer<String, MapRecord<String, String, String>>
      streamListenerContainer(RedisConnectionFactory cf, RedisStreamEventListener listener) {

    StreamMessageListenerContainer.StreamMessageListenerContainerOptions<
            String, MapRecord<String, String, String>>
        options =
            StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                .pollTimeout(props.pollTimeout())
                .batchSize(props.batchSize())
                .build();

    StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
        StreamMessageListenerContainer.create(cf, options);

    // cancelOnError(t -> false): Lettuce's `Connection closed` during a blocking XREADGROUP is a
    // routine TCP-lifecycle event (Railway network recycling, Redis idle timeout, deploy-time
    // socket teardown). The default predicate cancels the poller on first such error, which
    // silently stops event consumption until the whole worker is restarted. We keep the poller
    // alive and let Lettuce auto-reconnect (its own default) on the next iteration.
    //
    // errorHandler at WARN: drops the noise out of the ERROR pipeline so Arguslog no longer
    // ingests these as bugs. Real Redis errors (auth, OOM, command rejections) still surface —
    // Lettuce throws those with distinct exception types that downstream consumers can route on.
    ConsumerStreamReadRequest<String> readRequest =
        StreamMessageListenerContainer.StreamReadRequest.builder(
                StreamOffset.create(props.streamKey(), ReadOffset.lastConsumed()))
            .consumer(Consumer.from(props.consumerGroup(), props.consumerName()))
            .cancelOnError(t -> false)
            .errorHandler(
                t ->
                    log.warn(
                        "redis stream poll error on {}, will retry on next iteration",
                        props.streamKey(),
                        t))
            .build();

    container.register(readRequest, listener);

    return container;
  }
}
