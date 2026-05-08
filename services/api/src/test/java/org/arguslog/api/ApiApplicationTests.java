package org.arguslog.api;

import com.stripe.StripeClient;
import org.arguslog.api.alerts.application.port.AlertDestinationRepository;
import org.arguslog.api.alerts.application.port.AlertDestinationWriteRepository;
import org.arguslog.api.alerts.application.port.AlertRuleRepository;
import org.arguslog.api.alerts.application.port.AlertRuleWriteRepository;
import org.arguslog.api.application.port.DsnRepository;
import org.arguslog.api.application.port.DsnWriteRepository;
import org.arguslog.api.application.port.EventRepository;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.MembershipWriteRepository;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.application.port.ProjectRepository;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.application.port.UserRepository;
import org.arguslog.api.auth.application.port.PatRepository;
import org.arguslog.api.auth.application.port.TokenHasher;
import org.arguslog.api.billing.application.PortalUseCase;
import org.arguslog.api.billing.application.StripeWebhookUseCase;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.billing.application.port.StripeEventLog;
import org.arguslog.api.billing.application.port.StripeEventVerifier;
import org.arguslog.api.billing.application.port.UsageRepository;
import org.arguslog.api.email.InviteEmailSender;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactWriteRepository;
import org.arguslog.api.releases.application.port.SourceMapStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration"
    })
class ApiApplicationTests {

  // Every JDBC repository needs a DataSource which we excluded above; mock the ports so the
  // smoke test stays infrastructure-free (mirrors IngestApplicationTests / WorkerApplicationTests).
  @MockitoBean IssueRepository issueRepository;
  @MockitoBean EventRepository eventRepository;
  @MockitoBean ProjectRepository projectRepository;
  @MockitoBean MembershipRepository membershipRepository;
  @MockitoBean MembershipWriteRepository membershipWriteRepository;
  @MockitoBean InviteEmailSender inviteEmailSender;
  @MockitoBean AlertDestinationRepository alertDestinationRepository;
  @MockitoBean AlertDestinationWriteRepository alertDestinationWriteRepository;
  @MockitoBean AlertRuleRepository alertRuleRepository;
  @MockitoBean AlertRuleWriteRepository alertRuleWriteRepository;
  @MockitoBean OrgWriteRepository orgWriteRepository;
  @MockitoBean UserRepository userRepository;
  @MockitoBean ProjectWriteRepository projectWriteRepository;
  @MockitoBean DsnRepository dsnRepository;
  @MockitoBean DsnWriteRepository dsnWriteRepository;
  @MockitoBean ReleaseRepository releaseRepository;
  @MockitoBean SourceMapArtifactRepository sourceMapArtifactRepository;
  @MockitoBean SourceMapArtifactWriteRepository sourceMapArtifactWriteRepository;
  @MockitoBean SourceMapStorage sourceMapStorage;
  @MockitoBean PatRepository patRepository;
  @MockitoBean TokenHasher tokenHasher;
  @MockitoBean UsageRepository usageRepository;
  @MockitoBean OrgPlanRepository orgPlanRepository;
  @MockitoBean BillingCustomerRepository billingCustomerRepository;
  @MockitoBean PortalUseCase portalUseCase;
  @MockitoBean StripeWebhookUseCase stripeWebhookUseCase;
  @MockitoBean StripeEventLog stripeEventLog;
  @MockitoBean StripeEventVerifier stripeEventVerifier;
  @MockitoBean StripeClient stripeClient;

  @Test
  void contextLoads() {}
}
