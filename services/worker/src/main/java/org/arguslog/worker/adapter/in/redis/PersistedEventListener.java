package org.arguslog.worker.adapter.in.redis;

import java.time.Instant;
import java.util.Map;
import org.arguslog.worker.application.EvaluatePersistedEventUseCase;
import org.arguslog.worker.domain.PersistedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;

/**
 * Reads {@code events:persisted} on the {@code worker-alerts} consumer group, parses the wire
 * format produced by {@link org.arguslog.worker.adapter.out.redis.RedisPersistedEventPublisher},
 * and runs the rule evaluator. Acks every message — evaluation never throws (the use case wraps any
 * failure as "no matches"), and a poison entry shouldn't loop forever.
 */
public class PersistedEventListener
    implements StreamListener<String, MapRecord<String, String, String>> {

  private static final Logger log = LoggerFactory.getLogger(PersistedEventListener.class);

  private final EvaluatePersistedEventUseCase useCase;
  private final StringRedisTemplate redis;
  private final AlertStreamProperties props;

  public PersistedEventListener(
      EvaluatePersistedEventUseCase useCase,
      StringRedisTemplate redis,
      AlertStreamProperties props) {
    this.useCase = useCase;
    this.redis = redis;
    this.props = props;
  }

  @Override
  public void onMessage(MapRecord<String, String, String> record) {
    PersistedEvent event;
    try {
      event = parse(record.getValue());
    } catch (RuntimeException e) {
      log.warn(
          "dropping malformed persisted-event entry id={}: {}", record.getId(), e.getMessage());
      ack(record);
      return;
    }

    try {
      useCase.evaluate(event);
    } catch (RuntimeException e) {
      log.warn("rule evaluation failed for issue {}: {}", event.issueId(), e.getMessage());
      // intentional: ack anyway. A flaky DB will retry on the next event; we don't want one bad
      // event to clog the alerts pipeline behind it.
    }
    ack(record);
  }

  private void ack(MapRecord<String, String, String> record) {
    redis.opsForStream().acknowledge(props.streamKey(), props.consumerGroup(), record.getId());
  }

  private static PersistedEvent parse(Map<String, String> v) {
    return new PersistedEvent(
        Long.parseLong(require(v, "issueId")),
        Long.parseLong(require(v, "projectId")),
        require(v, "level"),
        Boolean.parseBoolean(v.getOrDefault("newIssue", "false")),
        Long.parseLong(require(v, "occurrenceCount")),
        Instant.parse(require(v, "firstSeenAt")),
        Instant.parse(require(v, "lastSeenAt")));
  }

  private static String require(Map<String, String> v, String key) {
    String value = v.get(key);
    if (value == null) throw new IllegalArgumentException("missing field: " + key);
    return value;
  }
}
