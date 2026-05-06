package org.arguslog.worker;

import org.arguslog.worker.application.port.AlertContextResolver;
import org.arguslog.worker.application.port.AlertDestinationRepository;
import org.arguslog.worker.application.port.AlertRuleRepository;
import org.arguslog.worker.application.port.EventStore;
import org.arguslog.worker.application.port.PersistedEventPublisher;
import org.arguslog.worker.application.port.RuleThrottle;
import org.arguslog.worker.application.port.SourceMapStore;
import org.arguslog.worker.application.port.SymbolicationRepository;
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
      "arguslog.worker.stream-enabled=false",
      "arguslog.worker.alerts.stream-enabled=false"
    })
class WorkerApplicationTests {

  // Mock every DB/Redis-bound port so the smoke test stays pure-context. Telegram
  // dispatcher is
  // fine to instantiate (no token => log-and-drop) so we don't mock it here.
  @MockitoBean EventStore eventStore;
  @MockitoBean AlertRuleRepository alertRuleRepository;
  @MockitoBean AlertDestinationRepository alertDestinationRepository;
  @MockitoBean AlertContextResolver alertContextResolver;
  @MockitoBean PersistedEventPublisher persistedEventPublisher;
  @MockitoBean RuleThrottle ruleThrottle;
  @MockitoBean SymbolicationRepository symbolicationRepository;
  @MockitoBean SourceMapStore sourceMapStore;

  @Test
  void contextLoads() {}
}
