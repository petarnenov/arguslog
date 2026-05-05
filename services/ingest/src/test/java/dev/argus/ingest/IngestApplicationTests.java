package dev.argus.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@TestPropertySource(
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
    })
class IngestApplicationTests {

  @MockitoBean dev.argus.ingest.application.port.EventStreamPublisher publisher;

  @Test
  void contextLoads() {}
}
