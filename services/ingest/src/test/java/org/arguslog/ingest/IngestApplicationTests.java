package org.arguslog.ingest;

import org.arguslog.ingest.application.port.EventStreamPublisher;
import org.arguslog.ingest.application.port.ProjectAuthenticator;
import org.arguslog.ingest.application.port.QuotaEnforcer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@TestPropertySource(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
    })
class IngestApplicationTests {

  @MockitoBean ProjectAuthenticator projectAuthenticator;
  @MockitoBean QuotaEnforcer quotaEnforcer;
  @MockitoBean EventStreamPublisher eventStreamPublisher;

  @Test
  void contextLoads() {}
}
