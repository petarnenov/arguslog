package dev.argus.api.adapter.in.web;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.argus.api.application.ListIssuesService.InvalidCursorException;
import dev.argus.api.application.ListIssuesUseCase;
import dev.argus.api.application.ListIssuesUseCase.Page;
import dev.argus.api.application.port.IssueRepository;
import dev.argus.api.application.port.MembershipRepository;
import dev.argus.api.application.port.ProjectRepository;
import dev.argus.api.domain.Issue;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
class IssueControllerTest {

  @Autowired MockMvc mvc;

  // Mocking the use case alone is not enough — JdbcIssueRepository / JdbcProjectRepository /
  // JdbcMembershipRepository would still try to wire a DataSource bean (excluded above).
  // Mock every port so the context is infra-free. ProjectAccessGuard is @Profile("!test"),
  // so it does not register here either.
  @MockitoBean ListIssuesUseCase listIssues;
  @MockitoBean IssueRepository issueRepository;
  @MockitoBean ProjectRepository projectRepository;
  @MockitoBean MembershipRepository membershipRepository;

  @Test
  void returnsPaginatedEnvelope() throws Exception {
    when(listIssues.list(any()))
        .thenReturn(
            new Page(
                List.of(
                    new Issue(
                        7L,
                        101L,
                        "fp-x",
                        Issue.Status.UNRESOLVED,
                        Issue.Level.ERROR,
                        "TypeError: x",
                        "render at app.js:42",
                        Instant.parse("2026-05-05T10:00:00Z"),
                        Instant.parse("2026-05-05T11:00:00Z"),
                        3L)),
                Optional.of("Mi4yLjI=")));

    mvc.perform(get("/api/v1/projects/101/issues").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.data", hasSize(1)))
        .andExpect(jsonPath("$.data[0].id").value(7))
        .andExpect(jsonPath("$.data[0].projectId").value(101))
        .andExpect(jsonPath("$.data[0].fingerprint").value("fp-x"))
        .andExpect(jsonPath("$.data[0].status").value("unresolved"))
        .andExpect(jsonPath("$.data[0].level").value("error"))
        .andExpect(jsonPath("$.data[0].title").value("TypeError: x"))
        .andExpect(jsonPath("$.data[0].culprit").value("render at app.js:42"))
        .andExpect(jsonPath("$.data[0].occurrenceCount").value(3))
        .andExpect(jsonPath("$.page.next").value("Mi4yLjI="));
  }

  @Test
  void omitsNextWhenLastPage() throws Exception {
    when(listIssues.list(any())).thenReturn(new Page(List.of(), Optional.empty()));
    mvc.perform(get("/api/v1/projects/101/issues"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", hasSize(0)))
        .andExpect(jsonPath("$.page.next").doesNotExist());
  }

  @Test
  void invalidCursorIsRejectedAsProblemJson() throws Exception {
    when(listIssues.list(any())).thenThrow(new InvalidCursorException("cursor missing separator"));
    mvc.perform(get("/api/v1/projects/101/issues?cursor=garbage"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid cursor"));
  }

  @Test
  void invalidStatusIsRejectedAsProblemJson() throws Exception {
    mvc.perform(get("/api/v1/projects/101/issues?status=bogus"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid filter"));
  }

  @Test
  void invalidLevelIsRejectedAsProblemJson() throws Exception {
    mvc.perform(get("/api/v1/projects/101/issues?level=critical"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid filter"));
  }
}
