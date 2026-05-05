package org.arguslog.worker;

import org.arguslog.worker.application.port.AlertRuleRepository;
import org.arguslog.worker.application.port.EventStore;
import org.arguslog.worker.application.port.PersistedEventPublisher;
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
      "argus.worker.stream-enabled=false",
      "argus.worker.alerts.stream-enabled=false"
    })
class WorkerApplicationTests {

  // EventStore + AlertRuleRepository need a DataSource; PersistedEventPublisher needs Redis.
  // Mock all DB/Redis-bound ports so the smoke test stays pure-context.
  @MockitoBean EventStore eventStore;
  @MockitoBean AlertRuleRepository alertRuleRepository;
  @MockitoBean PersistedEventPublisher persistedEventPublisher;

  @Test
  void contextLoads() {}
}
