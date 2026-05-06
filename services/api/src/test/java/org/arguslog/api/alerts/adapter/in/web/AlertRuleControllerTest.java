package org.arguslog.api.alerts.adapter.in.web;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.alerts.application.AlertRuleUseCase;
import org.arguslog.api.alerts.application.AlertRuleUseCase.InvalidAlertRuleException;
import org.arguslog.api.alerts.application.port.AlertDestinationRepository;
import org.arguslog.api.alerts.application.port.AlertRuleRepository;
import org.arguslog.api.alerts.domain.AlertRule;
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
class AlertRuleControllerTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  @MockitoBean AlertRuleUseCase useCase;
  @MockitoBean AlertRuleRepository alertRuleRepository;
  @MockitoBean AlertDestinationRepository alertDestinationRepository;
  @MockitoBean IssueRepository issueRepository;
  @MockitoBean EventRepository eventRepository;
  @MockitoBean ProjectRepository projectRepository;
  @MockitoBean MembershipRepository membershipRepository;
  @MockitoBean DsnRepository dsnRepository;
  @MockitoBean OrgWriteRepository orgWriteRepository;
  @MockitoBean ProjectWriteRepository projectWriteRepository;
  @MockitoBean UserRepository userRepository;
  @MockitoBean ReleaseRepository releaseRepository;
  @MockitoBean SourceMapArtifactRepository sourceMapArtifactRepository;
  @MockitoBean SourceMapStorage sourceMapStorage;
  @MockitoBean PatRepository patRepository;
  @MockitoBean TokenHasher tokenHasher;
  @MockitoBean UsageRepository usageRepository;
  @MockitoBean OrgPlanRepository orgPlanRepository;
  @MockitoBean BillingCustomerRepository billingCustomerRepository;
  @MockitoBean StripeClient stripeClient;

  @Test
  void listReturnsTheRules() throws Exception {
    when(useCase.list(101L)).thenReturn(List.of(sample(7L, "fatals")));

    mvc.perform(get("/api/v1/projects/101/alert-rules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id").value(7))
        .andExpect(jsonPath("$[0].name").value("fatals"))
        .andExpect(jsonPath("$[0].throttleSeconds").value(300))
        .andExpect(jsonPath("$[0].enabled").value(true))
        .andExpect(jsonPath("$[0].conditions.level.in[0]").value("fatal"))
        .andExpect(jsonPath("$[0].actions.destinationIds[0]").value(1));
  }

  @Test
  void postCreatesAndReturns201() throws Exception {
    when(useCase.create(eq(101L), eq("fatals"), any(), any(), eq(300), eq(true)))
        .thenReturn(sample(7L, "fatals"));

    String body =
        """
        { "name": "fatals",
          "conditions": {"level":{"in":["fatal"]}},
          "actions":    {"destinationIds":[1]},
          "throttleSeconds": 300,
          "enabled": true }
        """;
    mvc.perform(
            post("/api/v1/projects/101/alert-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.name").value("fatals"));
  }

  @Test
  void postOmittingOptionalFieldsAppliesDefaults() throws Exception {
    when(useCase.create(eq(101L), eq("x"), any(), any(), eq(300), eq(true)))
        .thenReturn(sample(7L, "x"));
    String body =
        """
        { "name": "x",
          "conditions": {},
          "actions": {"destinationIds":[1]} }
        """;
    mvc.perform(
            post("/api/v1/projects/101/alert-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());
    verify(useCase).create(eq(101L), eq("x"), any(), any(), eq(300), eq(true));
  }

  @Test
  void invalidRuleSurfacesAsProblemJson() throws Exception {
    when(useCase.create(anyLong(), anyString(), any(), any(), anyInt(), anyBoolean()))
        .thenThrow(new InvalidAlertRuleException("conditions.level.in entries must be one of …"));
    String body =
        """
        { "name": "x",
          "conditions": {"level":{"in":["critical"]}},
          "actions": {"destinationIds":[1]} }
        """;
    mvc.perform(
            post("/api/v1/projects/101/alert-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid alert rule"));
  }

  @Test
  void getOneReturnsTheRow() throws Exception {
    when(useCase.get(101L, 7L)).thenReturn(Optional.of(sample(7L, "fatals")));
    mvc.perform(get("/api/v1/projects/101/alert-rules/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(7));
  }

  @Test
  void getOneIs404WhenMissing() throws Exception {
    when(useCase.get(101L, 999L)).thenReturn(Optional.empty());
    mvc.perform(get("/api/v1/projects/101/alert-rules/999")).andExpect(status().isNotFound());
  }

  @Test
  void putUpdatesAndReturns200() throws Exception {
    when(useCase.update(eq(101L), eq(7L), eq("renamed"), any(), any(), eq(600), eq(false)))
        .thenReturn(Optional.of(sampleWith(7L, "renamed", 600, false)));
    String body =
        """
        { "name": "renamed",
          "conditions": {},
          "actions": {"destinationIds":[1]},
          "throttleSeconds": 600,
          "enabled": false }
        """;
    mvc.perform(
            put("/api/v1/projects/101/alert-rules/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("renamed"))
        .andExpect(jsonPath("$.throttleSeconds").value(600))
        .andExpect(jsonPath("$.enabled").value(false));
  }

  @Test
  void deleteReturns204() throws Exception {
    when(useCase.delete(101L, 7L)).thenReturn(true);
    mvc.perform(delete("/api/v1/projects/101/alert-rules/7")).andExpect(status().isNoContent());
  }

  @Test
  void deleteIs404WhenMissing() throws Exception {
    when(useCase.delete(101L, 999L)).thenReturn(false);
    mvc.perform(delete("/api/v1/projects/101/alert-rules/999")).andExpect(status().isNotFound());
  }

  private AlertRule sample(long id, String name) {
    return sampleWith(id, name, 300, true);
  }

  private AlertRule sampleWith(long id, String name, int throttle, boolean enabled) {
    try {
      return new AlertRule(
          id,
          101L,
          name,
          mapper.readTree("{\"level\":{\"in\":[\"fatal\"]}}"),
          mapper.readTree("{\"destinationIds\":[1]}"),
          throttle,
          enabled,
          Instant.parse("2026-05-05T12:00:00Z"));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
