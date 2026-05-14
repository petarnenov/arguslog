package org.arguslog.api.releases.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.arguslog.api.application.port.ProjectRepository;
import org.arguslog.api.releases.application.SourceMapArtifactUseCase.CreatedUpload;
import org.arguslog.api.releases.application.SourceMapArtifactUseCase.InvalidSourceMapException;
import org.arguslog.api.releases.application.SourceMapArtifactUseCase.ReleaseNotFoundException;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactRepository;
import org.arguslog.api.releases.application.port.SourceMapStorage;
import org.arguslog.api.releases.domain.Release;
import org.arguslog.api.releases.domain.SourceMapArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SourceMapArtifactServiceTest {

  @Mock ReleaseRepository releases;
  @Mock SourceMapArtifactRepository artifacts;
  @Mock org.arguslog.api.releases.application.port.SourceMapArtifactWriteRepository artifactWrites;
  @Mock SourceMapStorage storage;
  @Mock ProjectRepository projects;

  SourceMapArtifactService service;

  private static final Instant FIXED_NOW = Instant.parse("2026-05-05T12:00:00Z");
  private static final String VALID_SHA =
      "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

  @BeforeEach
  void setUp() {
    service =
        new SourceMapArtifactService(
            releases,
            artifacts,
            artifactWrites,
            storage,
            projects,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
  }

  @Test
  void persistsUpsertAndPresignsURL() {
    Release release = new Release(7L, 101L, "1.2.3", FIXED_NOW.minusSeconds(60));
    SourceMapArtifact stored =
        new SourceMapArtifact(
            42L, 7L, "1/101/7/dist/app.js.map", "dist/app.js", VALID_SHA, 1234L, FIXED_NOW);
    URI presigned = URI.create("https://r2.example/upload?sig=abc");

    when(releases.find(101L, 7L)).thenReturn(Optional.of(release));
    when(projects.findOrgIdForProject(101L)).thenReturn(OptionalLong.of(1L));
    when(artifactWrites.upsert(7L, "1/101/7/dist/app.js.map", "dist/app.js", VALID_SHA, 1234L))
        .thenReturn(stored);
    when(storage.presignPut(eq("1/101/7/dist/app.js.map"), eq(1234L), any(Duration.class)))
        .thenReturn(presigned);

    CreatedUpload out = service.create(101L, 7L, "dist/app.js", VALID_SHA, 1234L);

    assertThat(out.artifact()).isEqualTo(stored);
    assertThat(out.uploadUrl()).isEqualTo(presigned);
    assertThat(out.expiresAt()).isAfter(FIXED_NOW);
  }

  @Test
  void leadingSlashIsTrimmedFromR2Key() {
    when(releases.find(101L, 7L))
        .thenReturn(Optional.of(new Release(7L, 101L, "1.2.3", FIXED_NOW)));
    when(projects.findOrgIdForProject(101L)).thenReturn(OptionalLong.of(1L));
    when(artifactWrites.upsert(
            eq(7L), eq("1/101/7/dist/app.js.map"), eq("/dist/app.js"), eq(VALID_SHA), eq(1L)))
        .thenReturn(
            new SourceMapArtifact(
                1L, 7L, "1/101/7/dist/app.js.map", "/dist/app.js", VALID_SHA, 1L, FIXED_NOW));
    when(storage.presignPut(anyString(), anyLong(), any())).thenReturn(URI.create("https://x"));

    service.create(101L, 7L, "/dist/app.js", VALID_SHA, 1L);

    verify(artifactWrites).upsert(7L, "1/101/7/dist/app.js.map", "/dist/app.js", VALID_SHA, 1L);
  }

  @Test
  void missingReleaseRejectedBeforeStorage() {
    when(releases.find(101L, 999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(101L, 999L, "dist/a.js", VALID_SHA, 100L))
        .isInstanceOf(ReleaseNotFoundException.class);
    verifyNoInteractions(storage);
    verify(artifactWrites, never())
        .upsert(anyLong(), anyString(), anyString(), anyString(), anyLong());
  }

  @Test
  void blankPathRejected() {
    assertThatThrownBy(() -> service.create(101L, 7L, "  ", VALID_SHA, 100L))
        .isInstanceOf(InvalidSourceMapException.class);
    verifyNoInteractions(releases, projects, artifacts, storage);
  }

  @Test
  void pathWithDotDotRejected() {
    assertThatThrownBy(() -> service.create(101L, 7L, "../../etc/passwd.js", VALID_SHA, 100L))
        .isInstanceOf(InvalidSourceMapException.class)
        .hasMessageContaining("..");
    verifyNoInteractions(releases, projects, artifacts, storage);
  }

  @Test
  void overlongPathRejected() {
    String tooLong = "a".repeat(SourceMapArtifactService.MAX_PATH_LENGTH + 1);
    assertThatThrownBy(() -> service.create(101L, 7L, tooLong, VALID_SHA, 100L))
        .isInstanceOf(InvalidSourceMapException.class);
  }

  @Test
  void nonHexShaRejected() {
    assertThatThrownBy(() -> service.create(101L, 7L, "dist/a.js", "ZZZ", 100L))
        .isInstanceOf(InvalidSourceMapException.class)
        .hasMessageContaining("sha256");
    assertThatThrownBy(
            () ->
                service.create(
                    101L, 7L, "dist/a.js", "ABCDEF0123456789".repeat(4), 100L)) // uppercase
        .isInstanceOf(InvalidSourceMapException.class);
  }

  @Test
  void zeroOrNegativeSizeRejected() {
    assertThatThrownBy(() -> service.create(101L, 7L, "dist/a.js", VALID_SHA, 0L))
        .isInstanceOf(InvalidSourceMapException.class);
    assertThatThrownBy(() -> service.create(101L, 7L, "dist/a.js", VALID_SHA, -1L))
        .isInstanceOf(InvalidSourceMapException.class);
  }

  @Test
  void overlongSizeRejected() {
    assertThatThrownBy(
            () ->
                service.create(
                    101L, 7L, "dist/a.js", VALID_SHA, SourceMapArtifactService.MAX_SIZE_BYTES + 1))
        .isInstanceOf(InvalidSourceMapException.class)
        .hasMessageContaining("50 MiB");
  }

  @Test
  void listGoesThroughReleaseLookupForIsolation() {
    when(releases.find(101L, 7L))
        .thenReturn(Optional.of(new Release(7L, 101L, "1.2.3", FIXED_NOW)));
    when(artifacts.listForRelease(7L)).thenReturn(List.of());

    assertThat(service.list(101L, 7L)).isEmpty();
    verify(artifacts).listForRelease(7L);
  }

  @Test
  void listOnUnknownReleaseFails() {
    when(releases.find(101L, 999L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.list(101L, 999L)).isInstanceOf(ReleaseNotFoundException.class);
  }

  @Test
  void deleteDropsRowAndBestEffortBlob() {
    when(releases.find(101L, 7L))
        .thenReturn(Optional.of(new Release(7L, 101L, "1.2.3", FIXED_NOW)));
    SourceMapArtifact existing =
        new SourceMapArtifact(42L, 7L, "1/101/7/dist/a.js.map", "dist/a.js", VALID_SHA, 10L, FIXED_NOW);
    when(artifacts.findUnderRelease(7L, 42L)).thenReturn(Optional.of(existing));
    when(artifactWrites.delete(7L, 42L)).thenReturn(true);

    assertThat(service.delete(101L, 7L, 42L)).isTrue();
    verify(storage).deleteObject("1/101/7/dist/a.js.map");
  }

  @Test
  void deleteOnMissingArtifactReturnsFalseWithoutBlobCall() {
    when(releases.find(101L, 7L))
        .thenReturn(Optional.of(new Release(7L, 101L, "1.2.3", FIXED_NOW)));
    when(artifacts.findUnderRelease(7L, 9999L)).thenReturn(Optional.empty());

    assertThat(service.delete(101L, 7L, 9999L)).isFalse();
    verify(artifactWrites, never()).delete(anyLong(), anyLong());
    verify(storage, never()).deleteObject(anyString());
  }

  @Test
  void deleteOnUnknownReleaseRaises404() {
    when(releases.find(101L, 999L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete(101L, 999L, 1L))
        .isInstanceOf(ReleaseNotFoundException.class);
    verifyNoInteractions(artifactWrites);
    verifyNoInteractions(storage);
  }

  @Test
  void deleteSwallowsStorageFailure() {
    when(releases.find(101L, 7L))
        .thenReturn(Optional.of(new Release(7L, 101L, "1.2.3", FIXED_NOW)));
    SourceMapArtifact existing =
        new SourceMapArtifact(42L, 7L, "k.map", "dist/a.js", VALID_SHA, 10L, FIXED_NOW);
    when(artifacts.findUnderRelease(7L, 42L)).thenReturn(Optional.of(existing));
    when(artifactWrites.delete(7L, 42L)).thenReturn(true);
    org.mockito.Mockito.doThrow(new RuntimeException("r2 down"))
        .when(storage)
        .deleteObject("k.map");

    // Row was deleted even though the blob removal threw — service swallows the storage error
    // and surfaces success because the api row is the source of truth.
    assertThat(service.delete(101L, 7L, 42L)).isTrue();
  }
}
