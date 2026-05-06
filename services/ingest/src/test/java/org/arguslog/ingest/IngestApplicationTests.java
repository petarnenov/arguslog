package org.arguslog.ingest;

import org.arguslog.ingest.application.port.EventStreamPublisher;
import org.arguslog.ingest.application.port.MonthlyQuotaCounter;
import org.arguslog.ingest.application.port.ProjectAuthenticator;
import org.arguslog.ingest.application.port.ProjectQuotaContext;
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
  // RealQuotaEnforcer (the now-default @Component) pulls these in; both adapters need a
  // DataSource which the smoke test excludes, so mock the ports too.
  @MockitoBean ProjectQuotaContext projectQuotaContext;
  @MockitoBean MonthlyQuotaCounter monthlyQuotaCounter;

  @Test
  void contextLoads() {}
}
