package org.arguslog.api.billing.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
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
import org.arguslog.api.billing.application.StripeWebhookUseCase.Outcome;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.billing.application.port.StripeEventLog;
import org.arguslog.api.billing.application.port.StripeEventVerifier;
import org.arguslog.api.billing.application.port.UsageRepository;
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
          + "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration",
      // Webhook controller short-circuits with 503 when this is blank — give it a value so
      // the verifier-rejection / handler-success tests can exercise the real branches.
      "arguslog.stripe.webhook-secret=whsec_test_4_unit"
    })
class StripeWebhookControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean StripeWebhookUseCase useCase;
  @MockitoBean StripeEventVerifier verifier;
  @MockitoBean StripeEventLog stripeEventLog;
  @MockitoBean PortalUseCase portalUseCase;
  @MockitoBean UsageRepository usageRepository;
  @MockitoBean OrgPlanRepository orgPlanRepository;
  @MockitoBean BillingCustomerRepository billingCustomerRepository;
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
  void missingSignatureReturns400() throws Exception {
    mvc.perform(
            post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"evt_x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("missing_signature"));
    verify(useCase, never()).handle(any());
  }

  @Test
  void invalidSignatureReturns400() throws Exception {
    when(verifier.verify(eq("{\"id\":\"evt_x\"}"), eq("t=1,v1=bad"), eq("whsec_test_4_unit")))
        .thenThrow(new SignatureVerificationException("bad sig", "t=1,v1=bad"));

    mvc.perform(
            post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=1,v1=bad")
                .content("{\"id\":\"evt_x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_signature"));
    verify(useCase, never()).handle(any());
  }

  @Test
  void verifiedEventDelegatesToUseCaseAndReturnsOutcome() throws Exception {
    Event event = org.mockito.Mockito.mock(Event.class);
    when(verifier.verify(any(), any(), eq("whsec_test_4_unit"))).thenReturn(event);
    when(useCase.handle(event)).thenReturn(Outcome.PROCESSED);

    mvc.perform(
            post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=1,v1=ok")
                .content("{\"id\":\"evt_y\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("processed"));
  }

  @Test
  void duplicateEventOutcomeStillReturns200() throws Exception {
    Event event = org.mockito.Mockito.mock(Event.class);
    when(verifier.verify(any(), any(), any())).thenReturn(event);
    when(useCase.handle(event)).thenReturn(Outcome.ALREADY_SEEN);

    mvc.perform(
            post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=1,v1=ok")
                .content("{\"id\":\"evt_dup\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("already_seen"));
  }

  @Test
  void handlerCrashYields500SoStripeRedelivers() throws Exception {
    Event event = org.mockito.Mockito.mock(Event.class);
    when(verifier.verify(any(), any(), any())).thenReturn(event);
    when(useCase.handle(event)).thenThrow(new RuntimeException("db down"));

    mvc.perform(
            post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=1,v1=ok")
                .content("{\"id\":\"evt_z\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("handler_failed"));
  }
}
