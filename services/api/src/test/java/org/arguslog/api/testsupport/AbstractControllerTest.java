package org.arguslog.api.testsupport;

import com.stripe.StripeClient;
import org.arguslog.api.alerts.application.AlertDestinationUseCase;
import org.arguslog.api.alerts.application.AlertRuleUseCase;
import org.arguslog.api.alerts.application.port.AlertDestinationRepository;
import org.arguslog.api.alerts.application.port.AlertDestinationWriteRepository;
import org.arguslog.api.alerts.application.port.AlertRuleRepository;
import org.arguslog.api.alerts.application.port.AlertRuleWriteRepository;
import org.arguslog.api.application.GetIssueUseCase;
import org.arguslog.api.application.ListIssueEventsUseCase;
import org.arguslog.api.application.ListIssuesUseCase;
import org.arguslog.api.application.MemberUseCase;
import org.arguslog.api.application.PlatformUseCase;
import org.arguslog.api.application.port.DsnRepository;
import org.arguslog.api.application.port.DsnWriteRepository;
import org.arguslog.api.application.port.EventRepository;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.MembershipWriteRepository;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.application.port.PlatformRepository;
import org.arguslog.api.application.port.ProjectRepository;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.application.port.UserRepository;
import org.arguslog.api.auth.application.PatUseCase;
import org.arguslog.api.auth.application.port.PatRepository;
import org.arguslog.api.auth.application.port.TokenHasher;
import org.arguslog.api.billing.application.CheckoutUseCase;
import org.arguslog.api.billing.application.PortalUseCase;
import org.arguslog.api.billing.application.StripeWebhookUseCase;
import org.arguslog.api.billing.application.UsageUseCase;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.billing.application.port.StripeEventLog;
import org.arguslog.api.billing.application.port.StripeEventVerifier;
import org.arguslog.api.billing.application.port.UsageRepository;
import org.arguslog.api.email.InviteEmailSender;
import org.arguslog.api.releases.application.ReleaseUseCase;
import org.arguslog.api.releases.application.SourceMapArtifactUseCase;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactWriteRepository;
import org.arguslog.api.releases.application.port.SourceMapStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for every controller MockMvc test. Centralizes:
 *
 * <ul>
 *   <li>{@code @SpringBootTest} + {@code @AutoConfigureMockMvc} + {@code @ActiveProfiles("test")}
 *   <li>The autoconfigure exclusion list — DataSource / JPA / Redis / Flyway / OAuth2 are off in
 *       MVC tests because they don't have backing infrastructure.
 *   <li>Every {@code @MockitoBean} declaration the api context demands so it can boot. Each
 *       individual test only needs to {@code Mockito.when(...).thenReturn(...)} on the use case it
 *       actually exercises — no more 20-line mock walls in every controller test.
 * </ul>
 *
 * <p>{@code @MockitoBean} (Spring Boot 3.4+) replaces the matching bean in the context with a
 * Mockito mock. Doing it once on this base class means a new {@code @Component} that lands in api
 * doesn't break every single MockMvc test the way the previous "list every bean per file" pattern
 * did.
 *
 * <p>Subclasses are plain {@code class} — no annotations needed beyond {@code @Test} on the
 * methods. Inject the use case under test with a regular {@code @Autowired} field; the mock comes
 * from this class.
 */
@SpringBootTest
@AutoConfigureMockMvc
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
public abstract class AbstractControllerTest {

  @Autowired protected MockMvc mvc;

  // ── application use-cases ──────────────────────────────────────────────────────────────────

  @MockitoBean protected ListIssuesUseCase listIssuesUseCase;
  @MockitoBean protected ListIssueEventsUseCase listIssueEventsUseCase;
  @MockitoBean protected GetIssueUseCase getIssueUseCase;
  @MockitoBean protected AlertRuleUseCase alertRuleUseCase;
  @MockitoBean protected AlertDestinationUseCase alertDestinationUseCase;
  @MockitoBean protected PatUseCase patUseCase;
  @MockitoBean protected ReleaseUseCase releaseUseCase;
  @MockitoBean protected SourceMapArtifactUseCase sourceMapArtifactUseCase;
  @MockitoBean protected CheckoutUseCase checkoutUseCase;
  @MockitoBean protected PortalUseCase portalUseCase;
  @MockitoBean protected StripeWebhookUseCase stripeWebhookUseCase;
  @MockitoBean protected UsageUseCase usageUseCase;
  @MockitoBean protected MemberUseCase memberUseCase;
  @MockitoBean protected PlatformUseCase platformUseCase;

  // ── repositories ───────────────────────────────────────────────────────────────────────────

  @MockitoBean protected IssueRepository issueRepository;
  @MockitoBean protected EventRepository eventRepository;
  @MockitoBean protected ProjectRepository projectRepository;
  @MockitoBean protected ProjectWriteRepository projectWriteRepository;
  @MockitoBean protected MembershipRepository membershipRepository;
  @MockitoBean protected MembershipWriteRepository membershipWriteRepository;
  @MockitoBean protected OrgWriteRepository orgWriteRepository;
  @MockitoBean protected InviteEmailSender inviteEmailSender;
  @MockitoBean protected DsnRepository dsnRepository;
  @MockitoBean protected DsnWriteRepository dsnWriteRepository;
  @MockitoBean protected PlatformRepository platformRepository;
  @MockitoBean protected UserRepository userRepository;
  @MockitoBean protected AlertRuleRepository alertRuleRepository;
  @MockitoBean protected AlertRuleWriteRepository alertRuleWriteRepository;
  @MockitoBean protected AlertDestinationRepository alertDestinationRepository;
  @MockitoBean protected AlertDestinationWriteRepository alertDestinationWriteRepository;
  @MockitoBean protected PatRepository patRepository;
  @MockitoBean protected TokenHasher tokenHasher;
  @MockitoBean protected ReleaseRepository releaseRepository;
  @MockitoBean protected SourceMapArtifactRepository sourceMapArtifactRepository;
  @MockitoBean protected SourceMapArtifactWriteRepository sourceMapArtifactWriteRepository;
  @MockitoBean protected SourceMapStorage sourceMapStorage;

  // ── billing wiring ─────────────────────────────────────────────────────────────────────────

  @MockitoBean protected BillingCustomerRepository billingCustomerRepository;
  @MockitoBean protected OrgPlanRepository orgPlanRepository;
  @MockitoBean protected UsageRepository usageRepository;
  @MockitoBean protected StripeEventLog stripeEventLog;
  @MockitoBean protected StripeEventVerifier stripeEventVerifier;
  @MockitoBean protected StripeClient stripeClient;
}
