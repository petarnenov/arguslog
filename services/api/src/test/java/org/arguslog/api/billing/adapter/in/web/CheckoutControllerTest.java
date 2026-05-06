package org.arguslog.api.billing.adapter.in.web;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stripe.StripeClient;
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
import org.arguslog.api.billing.application.CheckoutUseCase;
import org.arguslog.api.billing.application.CheckoutUseCase.CheckoutFailedException;
import org.arguslog.api.billing.application.CheckoutUseCase.StripeNotConfiguredException;
import org.arguslog.api.billing.application.PortalUseCase;
import org.arguslog.api.billing.application.PortalUseCase.NoCustomerException;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.billing.application.port.UsageRepository;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactRepository;
import org.arguslog.api.releases.application.port.SourceMapStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class CheckoutControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean CheckoutUseCase useCase;
  @MockitoBean PortalUseCase portalUseCase;
  @MockitoBean BillingCustomerRepository billingCustomerRepository;
  @MockitoBean StripeClient stripeClient;
  @MockitoBean UsageRepository usageRepository;
  @MockitoBean OrgPlanRepository orgPlanRepository;
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
  void postReturnsCheckoutUrl() throws Exception {
    // Test profile has SecurityConfig in permit-all mode → no JWT in the security context, so
    // currentUserEmail() returns null. The use case still receives the orgId either way.
    when(useCase.createCheckoutUrl(eq(1L), any())).thenReturn("https://checkout.stripe.com/c/abc");

    mvc.perform(post("/api/v1/orgs/1/billing/checkout-session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/c/abc"));
  }

  @Test
  void unconfiguredStripeReturns503ProblemJson() throws Exception {
    when(useCase.createCheckoutUrl(eq(1L), any()))
        .thenThrow(new StripeNotConfiguredException("Stripe is not configured"));

    mvc.perform(post("/api/v1/orgs/1/billing/checkout-session"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.title").value(startsWith("Stripe not configured")));
  }

  @Test
  void stripeRejectionReturns502ProblemJson() throws Exception {
    when(useCase.createCheckoutUrl(eq(1L), any()))
        .thenThrow(new CheckoutFailedException("rate-limited", new RuntimeException()));

    mvc.perform(post("/api/v1/orgs/1/billing/checkout-session"))
        .andExpect(status().isBadGateway())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.title").value(startsWith("Stripe checkout failed")));
  }

  @Test
  void portalReturnsHostedUrl() throws Exception {
    when(portalUseCase.createPortalUrl(1L)).thenReturn("https://billing.stripe.com/p/sess_xyz");

    mvc.perform(post("/api/v1/orgs/1/billing/portal"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("https://billing.stripe.com/p/sess_xyz"));
  }

  @Test
  void portalWithoutCustomerReturns409ProblemJson() throws Exception {
    when(portalUseCase.createPortalUrl(1L))
        .thenThrow(new NoCustomerException("Org 1 has no Stripe customer yet"));

    mvc.perform(post("/api/v1/orgs/1/billing/portal"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.title").value(startsWith("No Stripe customer")));
  }
}
