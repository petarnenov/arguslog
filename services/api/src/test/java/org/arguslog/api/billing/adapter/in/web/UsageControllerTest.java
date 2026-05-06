package org.arguslog.api.billing.adapter.in.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stripe.StripeClient;
import java.util.Optional;
import org.arguslog.api.alerts.application.port.AlertDestinationRepository;
import org.arguslog.api.alerts.application.port.AlertRuleRepository;
import org.arguslog.api.application.port.DsnRepository;
import org.arguslog.api.application.port.EventRepository;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.application.port.ProjectRepository;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.application.port.UserRepository;
import org.arguslog.api.auth.application.port.PatRepository;
import org.arguslog.api.auth.application.port.TokenHasher;
import org.arguslog.api.billing.application.PortalUseCase;
import org.arguslog.api.billing.application.StripeWebhookUseCase;
import org.arguslog.api.billing.application.UsageUseCase;
import org.arguslog.api.billing.application.UsageUseCase.UsageSnapshot;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.billing.application.port.StripeEventLog;
import org.arguslog.api.billing.application.port.StripeEventVerifier;
import org.arguslog.api.billing.application.port.UsageRepository;
import org.arguslog.api.billing.domain.PlanTier;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactRepository;
import org.arguslog.api.releases.application.port.SourceMapStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
class UsageControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean UsageUseCase useCase;
  @MockitoBean UsageRepository usageRepository;
  @MockitoBean OrgPlanRepository orgPlanRepository;
  @MockitoBean BillingCustomerRepository billingCustomerRepository;
  @MockitoBean PortalUseCase portalUseCase;
  @MockitoBean StripeWebhookUseCase stripeWebhookUseCase;
  @MockitoBean StripeEventLog stripeEventLog;
  @MockitoBean StripeEventVerifier stripeEventVerifier;
  @MockitoBean StripeClient stripeClient;
  @MockitoBean IssueRepository issueRepository;
  @MockitoBean EventRepository eventRepository;
  @MockitoBean ProjectRepository projectRepository;
  @MockitoBean MembershipRepository membershipRepository;
  @MockitoBean AlertDestinationRepository alertDestinationRepository;
  @MockitoBean AlertRuleRepository alertRuleRepository;
  @MockitoBean DsnRepository dsnRepository;
  @MockitoBean OrgWriteRepository orgWriteRepository;
  @MockitoBean ProjectWriteRepository projectWriteRepository;
  @MockitoBean UserRepository userRepository;
  @MockitoBean ReleaseRepository releaseRepository;
  @MockitoBean SourceMapArtifactRepository sourceMapArtifactRepository;
  @MockitoBean SourceMapStorage sourceMapStorage;
  @MockitoBean PatRepository patRepository;
  @MockitoBean TokenHasher tokenHasher;

  @Test
  void getReturnsSerializedSnapshot() throws Exception {
    when(useCase.snapshot(1L))
        .thenReturn(Optional.of(new UsageSnapshot(PlanTier.PRO, 25_000L, 100_000L, 0.25, false)));

    mvc.perform(get("/api/v1/orgs/1/usage").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.plan").value("pro"))
        .andExpect(jsonPath("$.monthlyPriceCents").value(900))
        .andExpect(jsonPath("$.eventsUsed").value(25000))
        .andExpect(jsonPath("$.eventCap").value(100000))
        .andExpect(jsonPath("$.projectCap").value(10))
        .andExpect(jsonPath("$.retentionDays").value(30))
        .andExpect(jsonPath("$.ratio").value(0.25))
        .andExpect(jsonPath("$.exceeded").value(false));
  }

  @Test
  void unknownOrgReturns404() throws Exception {
    when(useCase.snapshot(eq(99L))).thenReturn(Optional.empty());
    mvc.perform(get("/api/v1/orgs/99/usage")).andExpect(status().isNotFound());
  }
}
