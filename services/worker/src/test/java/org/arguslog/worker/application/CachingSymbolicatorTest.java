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

  // ── stripHashSegment / normalizePath ────────────────────────────────────────

  @Test
  void stripsViteStyleEightHexHash() {
    assertThat(CachingSymbolicator.stripHashSegment("app.d7f2a8e1.js")).isEqualTo("app.js");
  }

  @Test
  void stripsWebpackTwentyHexHash() {
    assertThat(CachingSymbolicator.stripHashSegment("main.6a1f3b2cdd5e9f1b4d3c.js"))
        .isEqualTo("main.js");
  }

  @Test
  void stripsHashSandwichedBetweenDottedExtensions() {
    assertThat(CachingSymbolicator.stripHashSegment("bundle.abc12345.min.js"))
        .isEqualTo("bundle.min.js");
  }

  @Test
  void stripsHashInsideNestedPath() {
    assertThat(CachingSymbolicator.stripHashSegment("chunks/main.6a1f3b2c.js"))
        .isEqualTo("chunks/main.js");
  }

  @Test
  void stripsOnlyTheFirstHashSegmentPerFilename() {
    // Two consecutive hash-shaped segments is suspicious — almost certainly not a real bundle
    // output. Only strip the first to avoid mangling unexpected inputs.
    assertThat(CachingSymbolicator.stripHashSegment("foo.abcdef.deadbe.js"))
        .isEqualTo("foo.deadbe.js");
  }

  @Test
  void leavesNonHashDottedNamesAlone() {
    // Real words / SemVer / nested module names should never be confused for a hash.
    assertThat(CachingSymbolicator.stripHashSegment("Component.test.js")).isEqualTo("Component.test.js");
    assertThat(CachingSymbolicator.stripHashSegment("lodash.debounce.js")).isEqualTo("lodash.debounce.js");
    assertThat(CachingSymbolicator.stripHashSegment("version.generated.ts"))
        .isEqualTo("version.generated.ts");
    // SemVer-like ranges with a dot — not a hash, mixed digits + dot but short and not hex-only.
    assertThat(CachingSymbolicator.stripHashSegment("pkg.1.js")).isEqualTo("pkg.1.js");
  }

  @Test
  void leavesPathsWithoutTrailingExtensionAlone() {
    // The guard `(?=\\.[A-Za-z])` requires another extension to the right of the hash. A bare
    // `something.abcdef` (with nothing after) is most likely not a hashed bundle filename.
    assertThat(CachingSymbolicator.stripHashSegment("something.abcdef")).isEqualTo("something.abcdef");
  }

  @Test
  void mixedCaseHashShapesAreLeftAlone() {
    // Mixed case (uppercase+lowercase letters mixed in the segment) is almost never a hash —
    // bundlers emit pure-hex which is single-case by definition.
    assertThat(CachingSymbolicator.stripHashSegment("app.D7f2A8e1.js")).isEqualTo("app.D7f2A8e1.js");
  }

  @Test
  void uppercaseHexHashIsStripped() {
    // Some Rollup configs emit uppercase. Treated symmetrically with lowercase.
    assertThat(CachingSymbolicator.stripHashSegment("app.D7F2A8E1.js")).isEqualTo("app.js");
  }

  @Test
  void normalizePathStripsHostAndHashTogether() {
    assertThat(CachingSymbolicator.normalizePath("https://cdn.example.com/dist/app.d7f2a8e1.js"))
        .isEqualTo("dist/app.js");
  }

  @Test
  void normalizePathStripsLeadingSlashAndHash() {
    assertThat(CachingSymbolicator.normalizePath("/assets/main.6a1f3b2c.min.js"))
        .isEqualTo("assets/main.min.js");
  }

  @Test
  void hashStripEnablesLookupForHashedBundleFilename() {
    // End-to-end: SDK sends a hashed filename, CLI uploaded under the clean name, repository
    // is asked for the clean name (post-strip) and returns a hit.
    when(repository.findArtifact(101L, "1.0.0", "dist/app.js"))
        .thenReturn(Optional.of(new ArtifactRow(7L, "k", "sha")));
    when(store.fetch("k")).thenReturn(Optional.of(SAMPLE_MAP));

    String payload =
        "{\"release\":\"1.0.0\",\"exception\":{\"values\":[{\"stacktrace\":{\"frames\":["
            + "{\"filename\":\"https://cdn.example.com/dist/app.d7f2a8e1.js\",\"lineno\":1,\"colno\":0}"
            + "]}}]}}";

    String out = symbolicator.symbolicate(101L, payload);
    assertThat(out).contains("originalFilename");
  }
}
