package org.arguslog.worker.adapter.out.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.arguslog.worker.application.port.PersistedEventPublisher;
import org.arguslog.worker.domain.PersistedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>Tags get nested as a JSON-encoded string in the {@code tags} field so the surrounding
 * map-record shape stays primitive (Redis Streams require string values). Empty / null tags
 * land as {@code "{}"} so the listener can always parse without a null-guard branch.
 */
@Component
public class RedisPersistedEventPublisher implements PersistedEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(RedisPersistedEventPublisher.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final String streamKey;

  public RedisPersistedEventPublisher(
      StringRedisTemplate redis,
      ObjectMapper mapper,
      @Value("${arguslog.worker.persisted-stream-key:events:persisted}") String streamKey) {
    this.redis = redis;
    this.mapper = mapper;
    this.streamKey = streamKey;
  }

  @Override
  public void publish(PersistedEvent event) {
    Map<String, String> entry = new HashMap<>();
    entry.put("issueId", String.valueOf(event.issueId()));
    entry.put("projectId", String.valueOf(event.projectId()));
    entry.put("level", event.level());
    entry.put("newIssue", String.valueOf(event.newIssue()));
    entry.put("occurrenceCount", String.valueOf(event.occurrenceCount()));
    entry.put("firstSeenAt", event.firstSeenAt().toString());
    entry.put("lastSeenAt", event.lastSeenAt().toString());
    entry.put("tags", encodeTags(event.tags()));

    MapRecord<String, String, String> record =
        StreamRecords.mapBacked(entry).withStreamKey(streamKey);
    redis.opsForStream().add(record);
  }

  private String encodeTags(Map<String, String> tags) {
    if (tags == null || tags.isEmpty()) return "{}";
    try {
      return mapper.writeValueAsString(tags);
    } catch (JsonProcessingException e) {
      log.warn("could not encode tags for stream entry; emitting empty: {}", e.getMessage());
      return "{}";
    }
  }
}
