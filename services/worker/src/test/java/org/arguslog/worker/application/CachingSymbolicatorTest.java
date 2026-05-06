package org.arguslog.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.arguslog.worker.application.port.SourceMapStore;
import org.arguslog.worker.application.port.SymbolicationRepository;
import org.arguslog.worker.application.port.SymbolicationRepository.ArtifactRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CachingSymbolicatorTest {

  @Mock SymbolicationRepository repository;
  @Mock SourceMapStore store;

  private final ObjectMapper mapper = new ObjectMapper();
  private CachingSymbolicator symbolicator;

  // Minimal v3 sourcemap that maps (genLine=0, genCol=0) → (sources[0]="src/app.js", line=10,
  // col=4, name="render").
  // mappings: "AAUIA" — a single 5-field segment with absolute deltas (0, 0, 10, 4, 0) on line 0.
  // Encoding: 0='A', 0='A', 10→zigzag 20→0b10100=20→'U', 4→zigzag 8→'I', 0='A'.
  private static final String SAMPLE_MAP =
      "{\"version\":3,\"sources\":[\"src/app.js\"],\"names\":[\"render\"],\"mappings\":\"AAUIA\"}";

  @BeforeEach
  void setUp() {
    symbolicator = new CachingSymbolicator(repository, store, mapper);
  }

  @Test
  void returnsInputUnchangedWhenNoReleaseTag() {
    String payload = "{\"exception\":{\"values\":[]}}";
    String out = symbolicator.symbolicate(101L, payload);
    assertThat(out).isSameAs(payload); // no parse, no rebuild — short-circuit on missing release
  }

  @Test
  void returnsInputUnchangedWhenPayloadIsNotValidJson() {
    String payload = "not-json";
    assertThat(symbolicator.symbolicate(101L, payload)).isSameAs(payload);
  }

  @Test
  void returnsInputUnchangedWhenNoArtifactMatches() {
    when(repository.findArtifact(101L, "1.0.0", "dist/app.js")).thenReturn(Optional.empty());
    String payload =
        "{\"release\":\"1.0.0\",\"exception\":{\"values\":[{\"stacktrace\":{\"frames\":[{\"filename\":\"dist/app.js\",\"lineno\":1,\"colno\":0}]}}]}}";
    assertThat(symbolicator.symbolicate(101L, payload)).isSameAs(payload);
  }

  @Test
  void enrichesFrameWithOriginalCoordinates() throws Exception {
    when(repository.findArtifact(101L, "1.0.0", "dist/app.js"))
        .thenReturn(Optional.of(new ArtifactRow(7L, "1/101/7/dist/app.js.map", "sha")));
    when(store.fetch("1/101/7/dist/app.js.map")).thenReturn(Optional.of(SAMPLE_MAP));

    String payload =
        "{\"release\":\"1.0.0\",\"exception\":{\"values\":[{\"stacktrace\":{\"frames\":[{\"filename\":\"dist/app.js\",\"lineno\":1,\"colno\":0}]}}]}}";

    String out = symbolicator.symbolicate(101L, payload);

    assertThat(out).isNotEqualTo(payload);
    JsonNode root = mapper.readTree(out);
    JsonNode frame =
        root.path("exception").path("values").get(0).path("stacktrace").path("frames").get(0);
    assertThat(frame.path("originalFilename").asText()).isEqualTo("src/app.js");
    assertThat(frame.path("originalLineno").asInt()).isEqualTo(11); // 0-based 10 → 1-based 11
    assertThat(frame.path("originalColno").asInt()).isEqualTo(4);
    assertThat(frame.path("originalFunction").asText()).isEqualTo("render");
    // Originals preserved (raw toggle in P3 #11).
    assertThat(frame.path("filename").asText()).isEqualTo("dist/app.js");
    assertThat(frame.path("lineno").asInt()).isEqualTo(1);
  }

  @Test
  void cachesParsedMapAcrossInvocations() {
    when(repository.findArtifact(101L, "1.0.0", "dist/app.js"))
        .thenReturn(Optional.of(new ArtifactRow(7L, "k", "sha")));
    when(store.fetch("k")).thenReturn(Optional.of(SAMPLE_MAP));

    String payload =
        "{\"release\":\"1.0.0\",\"exception\":{\"values\":[{\"stacktrace\":{\"frames\":[{\"filename\":\"dist/app.js\",\"lineno\":1,\"colno\":0}]}}]}}";

    symbolicator.symbolicate(101L, payload);
    symbolicator.symbolicate(101L, payload);

    // Repository is consulted both times (cheap PG lookup), but R2 is hit only once.
    verify(repository, times(2)).findArtifact(101L, "1.0.0", "dist/app.js");
    verify(store, times(1)).fetch("k");
  }

  @Test
  void absoluteUrlFilenameStripsHostBeforeLookup() {
    when(repository.findArtifact(101L, "1.0.0", "dist/app.js"))
        .thenReturn(Optional.of(new ArtifactRow(7L, "k", "sha")));
    when(store.fetch("k")).thenReturn(Optional.of(SAMPLE_MAP));

    String payload =
        "{\"release\":\"1.0.0\",\"exception\":{\"values\":[{\"stacktrace\":{\"frames\":[{\"filename\":\"https://cdn.example.com/dist/app.js\",\"lineno\":1,\"colno\":0}]}}]}}";

    String out = symbolicator.symbolicate(101L, payload);
    assertThat(out).contains("originalFilename");
  }

  @Test
  void unparseableSourcemapBytesAreFailedOpen() {
    when(repository.findArtifact(101L, "1.0.0", "dist/app.js"))
        .thenReturn(Optional.of(new ArtifactRow(7L, "k", "sha")));
    when(store.fetch("k")).thenReturn(Optional.of("garbage"));

    String payload =
        "{\"release\":\"1.0.0\",\"exception\":{\"values\":[{\"stacktrace\":{\"frames\":[{\"filename\":\"dist/app.js\",\"lineno\":1,\"colno\":0}]}}]}}";

    // Returns input unchanged — bad sourcemap should not drop the event.
    assertThat(symbolicator.symbolicate(101L, payload)).isSameAs(payload);
  }

  @Test
  void repositoryThrowsAreFailedOpen() {
    when(repository.findArtifact(101L, "1.0.0", "dist/app.js"))
        .thenThrow(new RuntimeException("db down"));
    String payload =
        "{\"release\":\"1.0.0\",\"exception\":{\"values\":[{\"stacktrace\":{\"frames\":[{\"filename\":\"dist/app.js\",\"lineno\":1,\"colno\":0}]}}]}}";
    assertThat(symbolicator.symbolicate(101L, payload)).isSameAs(payload);
  }
}
