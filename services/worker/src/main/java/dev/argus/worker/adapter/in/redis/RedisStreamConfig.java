package dev.argus.worker.adapter.in.redis;

import dev.argus.worker.application.ProcessEventUseCase;
import jakarta.annotation.PostConstruct;
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

@Configuration
@EnableConfigurationProperties(RedisStreamProperties.class)
@ConditionalOnProperty(name = "argus.worker.stream-enabled", matchIfMissing = true)
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
      if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
        log.debug(
            "consumer group {} on stream {} already exists",
            props.consumerGroup(),
            props.streamKey());
      } else {
        throw e;
      }
    }
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

    container.receive(
        Consumer.from(props.consumerGroup(), props.consumerName()),
        StreamOffset.create(props.streamKey(), ReadOffset.lastConsumed()),
        listener);

    return container;
  }
}
