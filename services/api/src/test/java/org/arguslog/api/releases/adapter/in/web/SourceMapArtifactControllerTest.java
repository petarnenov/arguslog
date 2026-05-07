package org.arguslog.api.releases.adapter.in.web;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.arguslog.api.releases.application.SourceMapArtifactUseCase.CreatedUpload;
import org.arguslog.api.releases.application.SourceMapArtifactUseCase.InvalidSourceMapException;
import org.arguslog.api.releases.application.SourceMapArtifactUseCase.ReleaseNotFoundException;
import org.arguslog.api.releases.domain.SourceMapArtifact;
import org.arguslog.api.testsupport.AbstractControllerTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SourceMapArtifactControllerTest extends AbstractControllerTest {

  private static final String SHA = "a".repeat(64);

  @Test
  void postReturnsArtifactPlusUploadUrl() throws Exception {
    SourceMapArtifact stored =
        new SourceMapArtifact(
            42L,
            7L,
            "1/101/7/dist/app.js.map",
            "dist/app.js",
            SHA,
            1234L,
            Instant.parse("2026-05-05T12:00:00Z"));
    when(sourceMapArtifactUseCase.create(eq(101L), eq(7L), eq("dist/app.js"), eq(SHA), eq(1234L)))
        .thenReturn(
            new CreatedUpload(
                stored,
                URI.create("https://r2.example/upload?sig=abc"),
                Instant.parse("2026-05-05T12:05:00Z")));

    mvc.perform(
            post("/api/v1/projects/101/releases/7/sourcemaps")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"originalPath\":\"dist/app.js\",\"sha256\":\""
                        + SHA
                        + "\",\"sizeBytes\":1234}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.artifact.id").value(42))
        .andExpect(jsonPath("$.artifact.r2Key").value("1/101/7/dist/app.js.map"))
        .andExpect(jsonPath("$.artifact.originalPath").value("dist/app.js"))
        .andExpect(jsonPath("$.uploadUrl").value("https://r2.example/upload?sig=abc"))
        .andExpect(jsonPath("$.expiresAt").value("2026-05-05T12:05:00Z"));
  }

  @Test
  void invalidPayloadReturns400ProblemJson() throws Exception {
    when(sourceMapArtifactUseCase.create(eq(101L), eq(7L), eq(""), eq(SHA), eq(1L)))
        .thenThrow(new InvalidSourceMapException("originalPath is required"));

    mvc.perform(
            post("/api/v1/projects/101/releases/7/sourcemaps")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"originalPath\":\"\",\"sha256\":\"" + SHA + "\",\"sizeBytes\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value(startsWith("Invalid")));
  }

  @Test
  void missingReleaseReturns404ProblemJson() throws Exception {
    when(sourceMapArtifactUseCase.create(eq(101L), eq(999L), eq("dist/app.js"), eq(SHA), eq(1L)))
        .thenThrow(new ReleaseNotFoundException("release 999 not found"));

    mvc.perform(
            post("/api/v1/projects/101/releases/999/sourcemaps")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"originalPath\":\"dist/app.js\",\"sha256\":\""
                        + SHA
                        + "\",\"sizeBytes\":1}"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value(startsWith("Release")));
  }

  @Test
  void getListReturnsRowsForRelease() throws Exception {
    when(sourceMapArtifactUseCase.list(101L, 7L))
        .thenReturn(
            List.of(
                new SourceMapArtifact(
                    1L,
                    7L,
                    "k1.map",
                    "dist/app.js",
                    SHA,
                    1L,
                    Instant.parse("2026-05-05T11:00:00Z")),
                new SourceMapArtifact(
                    2L,
                    7L,
                    "k2.map",
                    "dist/vendor.js",
                    SHA,
                    2L,
                    Instant.parse("2026-05-05T11:00:00Z"))));

    mvc.perform(get("/api/v1/projects/101/releases/7/sourcemaps"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].originalPath").value("dist/app.js"))
        .andExpect(jsonPath("$[1].originalPath").value("dist/vendor.js"));
  }

  @Test
  void listOnMissingReleaseReturns404() throws Exception {
    when(sourceMapArtifactUseCase.list(101L, 999L))
        .thenThrow(new ReleaseNotFoundException("release 999 not found"));

    mvc.perform(get("/api/v1/projects/101/releases/999/sourcemaps"))
        .andExpect(status().isNotFound());
  }
}
