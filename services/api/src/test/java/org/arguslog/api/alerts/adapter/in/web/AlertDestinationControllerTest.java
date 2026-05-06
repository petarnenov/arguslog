package org.arguslog.api.alerts.adapter.in.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.alerts.application.AlertDestinationUseCase;
import org.arguslog.api.alerts.application.port.AlertDestinationRepository;
import org.arguslog.api.alerts.domain.AlertDestination;
import org.arguslog.api.alerts.domain.DestinationKind;
import org.arguslog.api.application.port.DsnRepository;
import org.arguslog.api.application.port.EventRepository;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.application.port.ProjectRepository;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.application.port.UserRepository;
import org.arguslog.api.releases.application.port.ReleaseRepository;
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
class AlertDestinationControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean AlertDestinationUseCase useCase;
  // Repository ports must still be mocked because their @Component implementations want
  // a DataSource (excluded above). OrgAccessGuard is @Profile("!test"), so the guard
  // doesn't fire either.
  @MockitoBean AlertDestinationRepository alertDestinationRepository;
  @MockitoBean org.arguslog.api.alerts.application.port.AlertRuleRepository alertRuleRepository;
  @MockitoBean IssueRepository issueRepository;
  @MockitoBean EventRepository eventRepository;
  @MockitoBean ProjectRepository projectRepository;
  @MockitoBean MembershipRepository membershipRepository;
  @MockitoBean DsnRepository dsnRepository;
  @MockitoBean OrgWriteRepository orgWriteRepository;
  @MockitoBean ProjectWriteRepository projectWriteRepository;
  @MockitoBean UserRepository userRepository;
  @MockitoBean ReleaseRepository releaseRepository;

  @Test
  void listReturnsScrubbedMetadataNeverConfig() throws Exception {
    when(useCase.list(1L))
        .thenReturn(
            List.of(
                new AlertDestination(
                    7L,
                    1L,
                    DestinationKind.TELEGRAM,
                    "ops",
                    "{\"chatId\":\"-100\",\"botToken\":\"super-secret-do-not-leak\"}",
                    Instant.parse("2026-05-05T10:00:00Z"))));

    mvc.perform(get("/api/v1/orgs/1/alert-destinations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id").value(7))
        .andExpect(jsonPath("$[0].kind").value("telegram"))
        .andExpect(jsonPath("$[0].name").value("ops"))
        // Critical: the controller must NOT serialize the config blob.
        .andExpect(jsonPath("$[0].config").doesNotExist())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("super-secret"))));
  }

  @Test
  void postCreatesAndReturns201() throws Exception {
    when(useCase.create(eq(1L), eq(DestinationKind.TELEGRAM), eq("ops"), any()))
        .thenReturn(
            new AlertDestination(
                7L,
                1L,
                DestinationKind.TELEGRAM,
                "ops",
                "{}",
                Instant.parse("2026-05-05T10:00:00Z")));

    String body =
        """
        { "kind": "telegram", "name": "ops",
          "config": { "chatId": "-100", "botToken": "abc:123" } }
        """;
    mvc.perform(
            post("/api/v1/orgs/1/alert-destinations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.kind").value("telegram"));
  }

  @Test
  void postWithUnknownKindIs400ProblemJson() throws Exception {
    String body = "{\"kind\":\"sms\",\"name\":\"x\",\"config\":{}}";
    mvc.perform(
            post("/api/v1/orgs/1/alert-destinations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid alert destination"))
        .andExpect(jsonPath("$.detail", startsWith("kind must be one of")));
  }

  @Test
  void postPropagatesValidationFromUseCase() throws Exception {
    when(useCase.create(anyLong(), any(), anyString(), any()))
        .thenThrow(
            new AlertDestinationUseCase.InvalidDestinationConfigException(
                "telegram destination requires a non-empty string 'botToken'"));
    String body = "{\"kind\":\"telegram\",\"name\":\"ops\",\"config\":{\"chatId\":\"-100\"}}";
    mvc.perform(
            post("/api/v1/orgs/1/alert-destinations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("botToken")));
  }

  @Test
  void getOneReturnsTheRowWhenFound() throws Exception {
    when(useCase.get(1L, 7L))
        .thenReturn(
            Optional.of(
                new AlertDestination(
                    7L,
                    1L,
                    DestinationKind.SLACK,
                    "ops",
                    "{}",
                    Instant.parse("2026-05-05T10:00:00Z"))));
    mvc.perform(get("/api/v1/orgs/1/alert-destinations/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("slack"));
  }

  @Test
  void getOneIs404WhenMissing() throws Exception {
    when(useCase.get(1L, 999L)).thenReturn(Optional.empty());
    mvc.perform(get("/api/v1/orgs/1/alert-destinations/999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Not found"));
  }

  @Test
  void putUpdatesAndReturns200() throws Exception {
    when(useCase.update(eq(1L), eq(7L), eq("renamed"), any()))
        .thenReturn(
            Optional.of(
                new AlertDestination(
                    7L,
                    1L,
                    DestinationKind.WEBHOOK,
                    "renamed",
                    "{}",
                    Instant.parse("2026-05-05T10:00:00Z"))));
    String body = "{\"kind\":\"webhook\",\"name\":\"renamed\",\"config\":{\"url\":\"https://x\"}}";
    mvc.perform(
            put("/api/v1/orgs/1/alert-destinations/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("renamed"));
  }

  @Test
  void deleteReturns204AndCallsUseCase() throws Exception {
    when(useCase.delete(1L, 7L)).thenReturn(true);
    mvc.perform(delete("/api/v1/orgs/1/alert-destinations/7")).andExpect(status().isNoContent());
    verify(useCase).delete(1L, 7L);
  }

  @Test
  void deleteIs404WhenMissing() throws Exception {
    when(useCase.delete(1L, 999L)).thenReturn(false);
    mvc.perform(delete("/api/v1/orgs/1/alert-destinations/999")).andExpect(status().isNotFound());
  }
}
