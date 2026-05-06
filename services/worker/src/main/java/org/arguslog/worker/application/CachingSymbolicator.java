package org.arguslog.worker.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Optional;
import org.arguslog.worker.adapter.out.sourcemap.SourceMapJsonParser;
import org.arguslog.worker.application.port.SourceMapStore;
import org.arguslog.worker.application.port.SymbolicationRepository;
import org.arguslog.worker.application.port.SymbolicationRepository.ArtifactRow;
import org.arguslog.worker.application.port.Symbolicator;
import org.arguslog.worker.domain.ParsedSourceMap;
import org.arguslog.worker.domain.ParsedSourceMap.OriginalLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Walks {@code payload.exception.values[*].stacktrace.frames[*]} and, when the payload carries a
 * {@code release} tag, decodes each frame's (lineno, colno) via the matching uploaded sourcemap.
 * Decoded fields are added in place ({@code originalFilename}, {@code originalLineno}, {@code
 * originalColno}, {@code originalFunction}) — the originals are kept for the dashboard's "raw"
 * toggle (P3 #11).
 *
 * <p>Cache: bounded LRU of {@link ParsedSourceMap} keyed by {@code r2_key}. 256 maps cover a
 * reasonable hot set for a single-process worker; larger deployments can lift this number once we
 * have telemetry on miss rates.
 *
 * <p>Failure policy is "fail open": any exception inside this method returns the input payload
 * unchanged. The downstream persister never sees the failure and the event still lands.
 */
@Service
public class CachingSymbolicator implements Symbolicator {

  private static final Logger log = LoggerFactory.getLogger(CachingSymbolicator.class);
  private static final int MAX_CACHED_MAPS = 256;

  private final SymbolicationRepository repository;
  private final SourceMapStore store;
  private final SourceMapJsonParser parser;
  private final ObjectMapper mapper;
  private final Cache<String, ParsedSourceMap> cache;

  public CachingSymbolicator(
      SymbolicationRepository repository, SourceMapStore store, ObjectMapper mapper) {
    this.repository = repository;
    this.store = store;
    this.parser = new SourceMapJsonParser(mapper);
    this.mapper = mapper;
    this.cache = Caffeine.newBuilder().maximumSize(MAX_CACHED_MAPS).build();
  }

  @Override
  public String symbolicate(long projectId, String rawPayload) {
    if (rawPayload == null || rawPayload.isEmpty()) return rawPayload;
    try {
      JsonNode root;
      try {
        root = mapper.readTree(rawPayload);
      } catch (RuntimeException | java.io.IOException e) {
        return rawPayload; // unparseable — let the rest of the pipeline drop or salvage it
      }
      String release = root.path("release").asText("");
      if (release.isEmpty()) return rawPayload; // SDK didn't tag a release
      JsonNode exception = root.path("exception").path("values");
      if (!exception.isArray() || exception.isEmpty()) return rawPayload;

      boolean changed = false;
      for (JsonNode value : exception) {
        JsonNode frames = value.path("stacktrace").path("frames");
        if (!frames.isArray()) continue;
        for (JsonNode frameNode : frames) {
          if (!frameNode.isObject()) continue;
          if (enrichFrame(projectId, release, (ObjectNode) frameNode)) changed = true;
        }
      }
      if (!changed) return rawPayload;
      return mapper.writeValueAsString(root);
    } catch (RuntimeException | java.io.IOException e) {
      // Defense in depth — anything we missed above must not propagate.
      log.warn("symbolicator failed for project {}: {}", projectId, e.getMessage());
      return rawPayload;
    }
  }

  private boolean enrichFrame(long projectId, String release, ObjectNode frame) {
    String filename = frame.path("filename").asText("");
    if (filename.isEmpty()) return false;
    String originalPath = normalizePath(filename);
    int lineno = frame.path("lineno").asInt(-1);
    int colno = frame.path("colno").asInt(-1);
    if (lineno <= 0 || colno < 0) return false;

    Optional<ParsedSourceMap> map = loadMap(projectId, release, originalPath);
    if (map.isEmpty()) return false;

    // Sourcemap v3 uses 0-based generated lines/columns; SDK frames are 1-based for line.
    Optional<OriginalLocation> hit = map.get().lookup(lineno - 1, colno);
    if (hit.isEmpty()) return false;
    OriginalLocation loc = hit.get();
    if (loc.source() != null) frame.put("originalFilename", loc.source());
    frame.put("originalLineno", loc.line() + 1); // back to 1-based for the SDK convention
    frame.put("originalColno", loc.column());
    if (loc.name() != null) frame.put("originalFunction", loc.name());
    return true;
  }

  private Optional<ParsedSourceMap> loadMap(long projectId, String release, String originalPath) {
    Optional<ArtifactRow> artifact;
    try {
      artifact = repository.findArtifact(projectId, release, originalPath);
    } catch (RuntimeException e) {
      log.warn(
          "artifact lookup failed for {}/{}/{}: {}",
          projectId,
          release,
          originalPath,
          e.getMessage());
      return Optional.empty();
    }
    if (artifact.isEmpty()) return Optional.empty();

    String key = artifact.get().r2Key();
    ParsedSourceMap cached = cache.getIfPresent(key);
    if (cached != null) return Optional.of(cached);

    Optional<String> body = store.fetch(key);
    if (body.isEmpty()) return Optional.empty();
    try {
      ParsedSourceMap parsed = parser.parse(body.get());
      cache.put(key, parsed);
      return Optional.of(parsed);
    } catch (RuntimeException e) {
      log.warn("sourcemap parse failed for {}: {}", key, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Strip everything before the path segment we expect to match what the CLI uploaded. SDKs send
   * full URLs ({@code https://cdn.example.com/dist/app.abc123.js}); CLI usually uploads with {@code
   * --name dist/app.js} (no host, no cache-bust hash). For now we only strip the URL host and
   * leading slash — the {@code --name} flag remains the source of truth for the upload-side
   * mapping. TODO(P4): smarter normalization (strip hash segments).
   */
  static String normalizePath(String filename) {
    String f = filename;
    int scheme = f.indexOf("://");
    if (scheme >= 0) {
      int host = f.indexOf('/', scheme + 3);
      f = host >= 0 ? f.substring(host + 1) : "";
    } else if (f.startsWith("/")) {
      f = f.substring(1);
    }
    return f;
  }
}
