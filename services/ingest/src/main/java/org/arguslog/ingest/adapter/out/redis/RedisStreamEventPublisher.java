package org.arguslog.ingest.adapter.out.redis;

import java.util.Map;
import org.arguslog.ingest.application.port.EventStreamPublisher;
import org.arguslog.ingest.domain.EventEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisStreamEventPublisher implements EventStreamPublisher {

  private final StringRedisTemplate redis;
  private final String streamKey;

  public RedisStreamEventPublisher(
      StringRedisTemplate redis,
      @Value("${arguslog.ingest.stream-key:events:incoming}") String streamKey) {
    this.redis = redis;
    this.streamKey = streamKey;
  }

  @Override
  public void publish(EventEnvelope envelope) {
    MapRecord<String, String, String> record =
        StreamRecords.mapBacked(
                Map.of(
                    "eventId", envelope.eventId().toString(),
                    "projectId", String.valueOf(envelope.projectId()),
                    "dsnPublicKey", envelope.dsnPublicKey(),
                    "receivedAt", envelope.receivedAt().toString(),
                    "rawPayload", envelope.rawPayload(),
                    "clientIp", nullSafe(envelope.clientIp()),
                    "userAgent", nullSafe(envelope.userAgent())))
            .withStreamKey(streamKey);
    redis.opsForStream().add(record);
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }
}
