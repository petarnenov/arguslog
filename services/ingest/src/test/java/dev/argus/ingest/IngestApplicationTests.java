package dev.argus.ingest;

import dev.argus.ingest.application.port.EventStreamPublisher;
import dev.argus.ingest.application.port.ProjectAuthenticator;
import dev.argus.ingest.application.port.QuotaEnforcer;
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

  @MockitoBean ProjectAuthenticator projectAuthenticator;
  @MockitoBean QuotaEnforcer quotaEnforcer;
  @MockitoBean EventStreamPublisher eventStreamPublisher;

  @Test
  void contextLoads() {}
}
