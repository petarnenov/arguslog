package org.arguslog.api.auth.adapter.in.web;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.arguslog.api.auth.application.PatUseCase;
import org.arguslog.api.auth.application.PatUseCase.InvalidPatException;
import org.arguslog.api.auth.application.PatUseCase.Issued;
import org.arguslog.api.auth.application.port.PatRepository;
import org.arguslog.api.auth.application.port.TokenHasher;
import org.arguslog.api.auth.domain.PersonalAccessToken;
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
import org.springframework.security.test.context.support.WithMockUser;
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
class MeTokensControllerTest {

  private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-05-05T12:00:00Z");

  @Autowired MockMvc mvc;

  @MockitoBean PatUseCase useCase;
  @MockitoBean PatRepository patRepository;
  @MockitoBean TokenHasher tokenHasher;
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
  @MockitoBean UsageRepository usageRepository;
  @MockitoBean OrgPlanRepository orgPlanRepository;

  @Test
  @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
  void postReturnsPlaintextOnceWithCreatedRow() throws Exception {
    PersonalAccessToken stored =
        new PersonalAccessToken(7L, USER, "ci-bot", "ABCDEFGH", null, null, NOW);
    when(useCase.create(eq(USER), eq("ci-bot"), isNull()))
        .thenReturn(new Issued(stored, "arglog_pat_ABCDEFGH_" + "x".repeat(48)));

    mvc.perform(
            post("/api/v1/me/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ci-bot\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.prefix").value("ABCDEFGH"))
        .andExpect(jsonPath("$.token").value(startsWith("arglog_pat_ABCDEFGH_")));
  }

  @Test
  @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
  void getListOmitsTheTokenField() throws Exception {
    when(useCase.list(USER))
        .thenReturn(
            List.of(new PersonalAccessToken(7L, USER, "ci-bot", "ABCDEFGH", null, null, NOW)));

    mvc.perform(get("/api/v1/me/tokens"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(7))
        .andExpect(jsonPath("$[0].prefix").value("ABCDEFGH"))
        .andExpect(jsonPath("$[0].token").doesNotExist());
  }

  @Test
  @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
  void deleteReturns204WhenRevoked() throws Exception {
    when(useCase.revoke(USER, 7L)).thenReturn(true);
    mvc.perform(delete("/api/v1/me/tokens/7")).andExpect(status().isNoContent());
    verify(useCase).revoke(USER, 7L);
  }

  @Test
  @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
  void deleteReturns404WhenMissing() throws Exception {
    when(useCase.revoke(USER, 999L)).thenReturn(false);
    mvc.perform(delete("/api/v1/me/tokens/999")).andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
  void blankNameReturns400ProblemJson() throws Exception {
    when(useCase.create(eq(USER), eq(""), any()))
        .thenThrow(new InvalidPatException("name is required"));

    mvc.perform(
            post("/api/v1/me/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value(startsWith("Invalid")));
  }
}
