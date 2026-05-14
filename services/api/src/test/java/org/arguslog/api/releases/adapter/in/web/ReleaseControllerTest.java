package org.arguslog.api.releases.adapter.in.web;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.arguslog.api.releases.application.ReleaseUseCase.ReleaseNotFoundException;
import org.arguslog.api.releases.domain.Release;
import org.arguslog.api.releases.domain.ReleaseInput;
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
    when(releaseUseCase.create(eq(101L), argThat(i -> "1.2.3".equals(i.version()))))
        .thenReturn(new Release(7L, 101L, "1.2.3", Instant.parse("2026-05-05T12:00:00Z")));

    mvc.perform(
            post("/api/v1/projects/101/releases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"1.2.3\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "7"))
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.version").value("1.2.3"));

    verify(releaseUseCase).create(eq(101L), argThat(i -> "1.2.3".equals(i.version())));
  }

  @Test
  void postWithFullMetadataForwardsAllFields() throws Exception {
    when(releaseUseCase.create(eq(101L), any(ReleaseInput.class)))
        .thenReturn(
            new Release(
                8L,
                101L,
                "1.3.0",
                Instant.parse("2026-05-14T12:00:00Z"),
                Instant.parse("2026-05-14T10:00:00Z"),
                "abc1234",
                "main",
                "production",
                "ship notes"));

    mvc.perform(
            post("/api/v1/projects/101/releases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"version\":\"1.3.0\",\"releasedAt\":\"2026-05-14T10:00:00Z\","
                        + "\"gitSha\":\"abc1234\",\"gitRef\":\"main\","
                        + "\"deployStage\":\"production\",\"changelog\":\"ship notes\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.gitSha").value("abc1234"))
        .andExpect(jsonPath("$.deployStage").value("production"))
        .andExpect(jsonPath("$.changelog").value("ship notes"));

    verify(releaseUseCase)
        .create(
            eq(101L),
            argThat(
                i ->
                    "1.3.0".equals(i.version())
                        && "abc1234".equals(i.gitSha())
                        && "main".equals(i.gitRef())
                        && "production".equals(i.deployStage())
                        && "ship notes".equals(i.changelog())));
  }

  @Test
  void blankVersionReturnsProblemBadRequest() throws Exception {
    when(releaseUseCase.create(eq(101L), argThat(i -> "  ".equals(i.version()))))
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
    when(releaseUseCase.create(eq(101L), argThat(i -> "1.2.3".equals(i.version()))))
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
    when(releaseUseCase.create(eq(101L), argThat(i -> "1.2.3".equals(i.version()))))
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

  @Test
  void putUpdatesReleaseVersion() throws Exception {
    when(releaseUseCase.update(
            eq(101L), eq(7L), argThat(i -> "2.0.0".equals(i.version()))))
        .thenReturn(new Release(7L, 101L, "2.0.0", Instant.parse("2026-05-05T12:00:00Z")));

    mvc.perform(
            put("/api/v1/projects/101/releases/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"2.0.0\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.version").value("2.0.0"));

    verify(releaseUseCase)
        .update(eq(101L), eq(7L), argThat(i -> "2.0.0".equals(i.version())));
  }

  @Test
  void putUnknownReleaseReturns404() throws Exception {
    when(releaseUseCase.update(eq(101L), eq(9999L), argThat(i -> "x".equals(i.version()))))
        .thenThrow(new ReleaseNotFoundException("release 9999 does not exist in project 101"));

    mvc.perform(
            put("/api/v1/projects/101/releases/9999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"x\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Release not found"));
  }

  @Test
  void putWithPatLackingReleasesWriteIs403() throws Exception {
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
            put("/api/v1/projects/101/releases/7")
                .with(authentication(patWithoutScope))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"2.0.0\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void deleteReturns204() throws Exception {
    when(releaseUseCase.delete(101L, 7L)).thenReturn(true);
    mvc.perform(delete("/api/v1/projects/101/releases/7")).andExpect(status().isNoContent());
    verify(releaseUseCase).delete(101L, 7L);
  }

  @Test
  void deleteUnknownReleaseReturns404() throws Exception {
    when(releaseUseCase.delete(101L, 9999L)).thenReturn(false);
    mvc.perform(delete("/api/v1/projects/101/releases/9999")).andExpect(status().isNotFound());
  }

  @Test
  void deleteWithPatLackingReleasesWriteIs403() throws Exception {
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
                EnumSet.of(PatScope.RELEASES_READ)));

    mvc.perform(delete("/api/v1/projects/101/releases/7").with(authentication(patWithoutScope)))
        .andExpect(status().isForbidden());
  }
}
