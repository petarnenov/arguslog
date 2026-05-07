package org.arguslog.api.releases.adapter.in.web;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.auth.adapter.in.web.PatAuthenticationFilter.PatAuthentication;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.auth.domain.PersonalAccessToken;
import org.arguslog.api.releases.application.ReleaseUseCase.DuplicateReleaseException;
import org.arguslog.api.releases.application.ReleaseUseCase.InvalidReleaseException;
import org.arguslog.api.releases.domain.Release;
import org.arguslog.api.testsupport.AbstractControllerTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ReleaseControllerTest extends AbstractControllerTest {

  @Test
  void listReturnsReleasesNewestFirst() throws Exception {
    when(releaseUseCase.list(101L))
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
    when(releaseUseCase.create(eq(101L), eq("1.2.3")))
        .thenReturn(new Release(7L, 101L, "1.2.3", Instant.parse("2026-05-05T12:00:00Z")));

    mvc.perform(
            post("/api/v1/projects/101/releases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"1.2.3\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "7"))
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.version").value("1.2.3"));

    verify(releaseUseCase).create(101L, "1.2.3");
  }

  @Test
  void blankVersionReturnsProblemBadRequest() throws Exception {
    when(releaseUseCase.create(eq(101L), eq("  ")))
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
    when(releaseUseCase.create(eq(101L), eq("1.2.3")))
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
    when(releaseUseCase.get(101L, 9999L)).thenReturn(Optional.empty());

    mvc.perform(get("/api/v1/projects/101/releases/9999")).andExpect(status().isNotFound());
  }

  @Test
  void postWithPatLackingReleasesWriteIs403() throws Exception {
    PatAuthentication patWithoutScope =
        new PatAuthentication(
            new PersonalAccessToken(
                7L,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "ci",
                "ABCDEFGH",
                null,
                null,
                Instant.parse("2026-05-05T12:00:00Z"),
                EnumSet.of(PatScope.ISSUES_READ)));

    mvc.perform(
            post("/api/v1/projects/101/releases")
                .with(authentication(patWithoutScope))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"1.2.3\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void postWithPatHoldingReleasesWriteSucceeds() throws Exception {
    PatAuthentication patWithScope =
        new PatAuthentication(
            new PersonalAccessToken(
                7L,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "ci",
                "ABCDEFGH",
                null,
                null,
                Instant.parse("2026-05-05T12:00:00Z"),
                EnumSet.of(PatScope.RELEASES_WRITE)));
    when(releaseUseCase.create(eq(101L), eq("1.2.3")))
        .thenReturn(new Release(7L, 101L, "1.2.3", Instant.parse("2026-05-05T12:00:00Z")));

    mvc.perform(
            post("/api/v1/projects/101/releases")
                .with(authentication(patWithScope))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"1.2.3\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void getReturnsTheRelease() throws Exception {
    when(releaseUseCase.get(101L, 7L))
        .thenReturn(
            Optional.of(new Release(7L, 101L, "1.2.3", Instant.parse("2026-05-05T12:00:00Z"))));

    mvc.perform(get("/api/v1/projects/101/releases/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("1.2.3"));
  }
}
