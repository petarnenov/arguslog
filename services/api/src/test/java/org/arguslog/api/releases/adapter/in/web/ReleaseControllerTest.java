package org.arguslog.api.releases.adapter.in.web;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
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
import org.arguslog.api.releases.application.ReleaseUseCase;
import org.arguslog.api.releases.application.ReleaseUseCase.DuplicateReleaseException;
import org.arguslog.api.releases.application.ReleaseUseCase.InvalidReleaseException;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactRepository;
import org.arguslog.api.releases.application.port.SourceMapStorage;
import org.arguslog.api.releases.domain.Release;
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
class ReleaseControllerTest {

  @Autowired MockMvc mvc;

  @MockitoBean ReleaseUseCase useCase;
  // @Component JDBC adapters need a DataSource (excluded above) — mock every port so the
  // application context still wires.
  @MockitoBean ReleaseRepository releaseRepository;
  @MockitoBean SourceMapArtifactRepository sourceMapArtifactRepository;
  @MockitoBean SourceMapStorage sourceMapStorage;
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

  @Test
  void listReturnsReleasesNewestFirst() throws Exception {
    when(useCase.list(101L))
        .thenReturn(
            List.of(
                new Release(2L, 101L, "1.0.1", Instant.parse("2026-05-05T12:00:00Z")),
                new Release(1L, 101L, "1.0.0", Instant.parse("2026-05-05T11:00:00Z"))));

    mvc.perform(get("/api/v1/projects/101/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(2))
        .andExpect(jsonPath("$[0].version").value("1.0.1"))
        .andExpect(jsonPath("$[1].id").value(1));
  }

  @Test
  void postCreatesAndReturns201() throws Exception {
    when(useCase.create(eq(101L), eq("1.2.3")))
        .thenReturn(new Release(7L, 101L, "1.2.3", Instant.parse("2026-05-05T12:00:00Z")));

    mvc.perform(
            post("/api/v1/projects/101/releases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"1.2.3\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "7"))
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.version").value("1.2.3"));

    verify(useCase).create(101L, "1.2.3");
  }

  @Test
  void blankVersionReturnsProblemBadRequest() throws Exception {
    when(useCase.create(eq(101L), eq("  ")))
        .thenThrow(new InvalidReleaseException("version is required"));

    mvc.perform(
            post("/api/v1/projects/101/releases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value(startsWith("Invalid")))
        .andExpect(jsonPath("$.detail").value("version is required"));
  }

  @Test
  void duplicateVersionReturnsProblemConflict() throws Exception {
    when(useCase.create(eq(101L), eq("1.2.3")))
        .thenThrow(new DuplicateReleaseException("release version already exists"));

    mvc.perform(
            post("/api/v1/projects/101/releases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"1.2.3\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value(startsWith("Duplicate")));
  }

  @Test
  void getMissingReturns404() throws Exception {
    when(useCase.get(101L, 9999L)).thenReturn(Optional.empty());

    mvc.perform(get("/api/v1/projects/101/releases/9999")).andExpect(status().isNotFound());
  }

  @Test
  void getReturnsTheRelease() throws Exception {
    when(useCase.get(101L, 7L))
        .thenReturn(
            Optional.of(new Release(7L, 101L, "1.2.3", Instant.parse("2026-05-05T12:00:00Z"))));

    mvc.perform(get("/api/v1/projects/101/releases/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("1.2.3"));
  }
}
