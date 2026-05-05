package dev.argus.worker;

import dev.argus.worker.application.port.EventStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@TestPropertySource(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
      "argus.worker.stream-enabled=false"
    })
class WorkerApplicationTests {

  // EventStore needs a DataSource via JdbcEventStore; mock it so the smoke test stays
  // pure-context (matches the IngestApplicationTests stance).
  @MockitoBean EventStore eventStore;

  @Test
  void contextLoads() {}
}
