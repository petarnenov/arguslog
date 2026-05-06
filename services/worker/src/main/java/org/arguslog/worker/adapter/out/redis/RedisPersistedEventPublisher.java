package org.arguslog.worker.adapter.out.redis;

import java.util.Map;
import org.arguslog.worker.application.port.PersistedEventPublisher;
import org.arguslog.worker.domain.PersistedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Wire format mirrors the ingest publisher's choice (string-keyed map record) so the listener
 * round-trips without a custom serializer. Stream key defaults to {@code events:persisted}; the
 * dispatcher consumes via the {@code worker-alerts} consumer group so back-pressure cannot stall
 * event ingestion.
 */
@Component
public class RedisPersistedEventPublisher implements PersistedEventPublisher {

  private final StringRedisTemplate redis;
  private final String streamKey;

  public RedisPersistedEventPublisher(
      StringRedisTemplate redis,
      @Value("${arguslog.worker.persisted-stream-key:events:persisted}") String streamKey) {
    this.redis = redis;
    this.streamKey = streamKey;
  }

  @Override
  public void publish(PersistedEvent event) {
    MapRecord<String, String, String> record =
        StreamRecords.mapBacked(
                Map.of(
                    "issueId", String.valueOf(event.issueId()),
                    "projectId", String.valueOf(event.projectId()),
                    "level", event.level(),
                    "newIssue", String.valueOf(event.newIssue()),
                    "occurrenceCount", String.valueOf(event.occurrenceCount()),
                    "firstSeenAt", event.firstSeenAt().toString(),
                    "lastSeenAt", event.lastSeenAt().toString()))
            .withStreamKey(streamKey);
    redis.opsForStream().add(record);
  }
}
