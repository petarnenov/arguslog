package org.arguslog.worker.adapter.in.redis;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.arguslog.worker.application.ProcessEventUseCase;
import org.arguslog.worker.application.ProcessEventUseCase.Result;
import org.arguslog.worker.domain.IncomingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;

/**
 * Bridges one Redis Stream record to {@link ProcessEventUseCase}. Acks via {@code XACK} only when
 * the use case reports a terminal outcome ({@code Persisted}, {@code SalvagedAsUnknown}). On a
 * {@code Retryable} result the message stays in the pending list and Redis redelivers after the
 * group's idle threshold.
 */
public class RedisStreamEventListener
    implements StreamListener<String, MapRecord<String, String, String>> {

  private static final Logger log = LoggerFactory.getLogger(RedisStreamEventListener.class);

  private final ProcessEventUseCase useCase;
  private final StringRedisTemplate redis;
  private final RedisStreamProperties props;

  public RedisStreamEventListener(
      ProcessEventUseCase useCase, StringRedisTemplate redis, RedisStreamProperties props) {
    this.useCase = useCase;
    this.redis = redis;
    this.props = props;
  }

  @Override
  public void onMessage(MapRecord<String, String, String> record) {
    IncomingEvent event;
    try {
      event = parse(record.getValue());
    } catch (RuntimeException e) {
      log.warn(
          "Dropping malformed stream entry id={} (cannot ack-or-skip without a UUID): {}",
          record.getId(),
          e.getMessage());
      ack(record); // ack so we don't loop on poison input; payload is lost.
      return;
    }

    Result result = useCase.process(event);
    switch (result) {
      case Result.Persisted p -> {
        log.debug(
            "event {} → issue {} ({}); ack",
            event.eventId(),
            p.issueId(),
            p.newIssue() ? "new" : "bumped");
        ack(record);
      }
      case Result.SalvagedAsUnknown s -> {
        log.info("event {} salvaged as unknown → issue {}; ack", event.eventId(), s.issueId());
        ack(record);
      }
      case Result.Retryable r ->
          log.warn(
              "event {} transient failure: {}; leaving for redelivery",
              event.eventId(),
              r.reason());
    }
  }

  private void ack(MapRecord<String, String, String> record) {
    redis.opsForStream().acknowledge(props.streamKey(), props.consumerGroup(), record.getId());
  }

  private static IncomingEvent parse(Map<String, String> v) {
    return new IncomingEvent(
        UUID.fromString(require(v, "eventId")),
        Long.parseLong(require(v, "projectId")),
        require(v, "dsnPublicKey"),
        Instant.parse(require(v, "receivedAt")),
        require(v, "rawPayload"),
        v.getOrDefault("clientIp", ""),
        v.getOrDefault("userAgent", ""));
  }

  private static String require(Map<String, String> v, String key) {
    String value = v.get(key);
    if (value == null) {
      throw new IllegalArgumentException("missing field: " + key);
    }
    return value;
  }
}
