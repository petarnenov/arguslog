package org.arguslog.worker.adapter.in.redis;

import jakarta.annotation.PostConstruct;
import org.arguslog.worker.application.EvaluatePersistedEventUseCase;
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

/**
 * Boots the rule-evaluator listener on its own consumer group. Mirrors {@link RedisStreamConfig}.
 */
@Configuration
@EnableConfigurationProperties(AlertStreamProperties.class)
@ConditionalOnProperty(name = "arguslog.worker.alerts.stream-enabled", matchIfMissing = true)
public class AlertStreamConfig {

  private static final Logger log = LoggerFactory.getLogger(AlertStreamConfig.class);

  private final AlertStreamProperties props;
  private final StringRedisTemplate redis;

  public AlertStreamConfig(AlertStreamProperties props, StringRedisTemplate redis) {
    this.props = props;
    this.redis = redis;
  }

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
      if (msg != null && msg.contains("BUSYGROUP")) return true;
    }
    return false;
  }

  @Bean
  public PersistedEventListener persistedEventListener(
      EvaluatePersistedEventUseCase useCase, com.fasterxml.jackson.databind.ObjectMapper mapper) {
    return new PersistedEventListener(useCase, redis, mapper, props);
  }

  @Bean(initMethod = "start", destroyMethod = "stop")
  public StreamMessageListenerContainer<String, MapRecord<String, String, String>>
      alertStreamListenerContainer(RedisConnectionFactory cf, PersistedEventListener listener) {

    StreamMessageListenerContainer.StreamMessageListenerContainerOptions<
            String, MapRecord<String, String, String>>
        options =
            StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                .pollTimeout(props.pollTimeout())
                .batchSize(props.batchSize())
                .build();

    StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
        StreamMessageListenerContainer.create(cf, options);

    // See RedisStreamConfig for the rationale on cancelOnError(false) + WARN errorHandler.
    ConsumerStreamReadRequest<String> readRequest =
        StreamMessageListenerContainer.StreamReadRequest.builder(
                StreamOffset.create(props.streamKey(), ReadOffset.lastConsumed()))
            .consumer(Consumer.from(props.consumerGroup(), props.consumerName()))
            .cancelOnError(t -> false)
            .errorHandler(
                t ->
                    log.warn(
                        "alert stream poll error on {}, will retry on next iteration",
                        props.streamKey(),
                        t))
            .build();

    container.register(readRequest, listener);

    return container;
  }
}
