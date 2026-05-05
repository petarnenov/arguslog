package dev.argus.worker.adapter.in.redis;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.argus.worker.application.ProcessEventUseCase;
import dev.argus.worker.application.ProcessEventUseCase.Result;
import dev.argus.worker.domain.IncomingEvent;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class RedisStreamEventListenerTest {

  @Mock ProcessEventUseCase useCase;
  @Mock StringRedisTemplate redis;
  @Mock StreamOperations<String, Object, Object> streamOps;

  RedisStreamProperties props;
  RedisStreamEventListener listener;

  @BeforeEach
  void setUp() {
    props =
        new RedisStreamProperties(
            "events:incoming", "worker", "worker-1", 50, Duration.ofMillis(1000));
    listener = new RedisStreamEventListener(useCase, redis, props);
  }

  @Test
  void persistedResultIsAcked() {
    when(useCase.process(any(IncomingEvent.class))).thenReturn(new Result.Persisted(7L, true));
    when(redis.opsForStream()).thenReturn(streamOps);

    MapRecord<String, String, String> record = sampleRecord();
    listener.onMessage(record);

    verify(streamOps).acknowledge("events:incoming", "worker", record.getId());
  }

  @Test
  void salvagedResultIsAcked() {
    when(useCase.process(any(IncomingEvent.class))).thenReturn(new Result.SalvagedAsUnknown(99L));
    when(redis.opsForStream()).thenReturn(streamOps);

    MapRecord<String, String, String> record = sampleRecord();
    listener.onMessage(record);

    verify(streamOps).acknowledge(eq("events:incoming"), eq("worker"), any(RecordId.class));
  }

  @Test
  void retryableResultDoesNotAck() {
    when(useCase.process(any(IncomingEvent.class)))
        .thenReturn(new Result.Retryable("connection lost"));

    listener.onMessage(sampleRecord());

    verify(redis, never()).opsForStream();
  }

  @Test
  void malformedPayloadIsAckedSoTheStreamDoesNotLoopOnPoisonInput() {
    when(redis.opsForStream()).thenReturn(streamOps);

    Map<String, String> bad = new HashMap<>();
    bad.put("eventId", "not-a-uuid");
    MapRecord<String, String, String> record =
        MapRecord.create("events:incoming", bad).withId(RecordId.of("123-0"));

    listener.onMessage(record);

    verify(useCase, never()).process(any());
    verify(streamOps).acknowledge(eq("events:incoming"), eq("worker"), any(RecordId.class));
  }

  private static MapRecord<String, String, String> sampleRecord() {
    Map<String, String> v = new HashMap<>();
    v.put("eventId", UUID.randomUUID().toString());
    v.put("projectId", "101");
    v.put("dsnPublicKey", "pk");
    v.put("receivedAt", "2026-05-05T12:00:00Z");
    v.put("rawPayload", "{}");
    v.put("clientIp", "127.0.0.1");
    v.put("userAgent", "JUnit");
    return MapRecord.create("events:incoming", v).withId(RecordId.of("1700000000000-0"));
  }
}
