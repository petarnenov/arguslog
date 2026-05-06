package org.arguslog.worker.adapter.out.sourcemap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.arguslog.worker.adapter.out.sourcemap.Vlq.Cursor;
import org.arguslog.worker.domain.ParsedSourceMap;
import org.arguslog.worker.domain.ParsedSourceMap.Segment;

/**
 * Decodes a sourcemap v3 JSON document into a {@link ParsedSourceMap}. The parser walks the {@code
 * mappings} string once and decodes each segment's relative deltas into absolute generated/source
 * coordinates per the spec.
 *
 * <p>Sections (composite sourcemaps) and {@code sourceRoot} are intentionally not handled — the CLI
 * uploads single, self-contained .map files for now, and a partial implementation would just
 * silently misalign frames.
 */
public final class SourceMapJsonParser {

  private final ObjectMapper mapper;

  public SourceMapJsonParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public ParsedSourceMap parse(String json) {
    JsonNode root;
    try {
      root = mapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException("not valid JSON: " + e.getMessage(), e);
    }
    int version = root.path("version").asInt(-1);
    if (version != 3) {
      throw new IllegalArgumentException("unsupported sourcemap version " + version);
    }
    if (root.has("sections")) {
      throw new IllegalArgumentException("indexed (sectioned) sourcemaps are not supported");
    }

    List<String> sources = stringArray(root.get("sources"));
    List<String> names = stringArray(root.get("names"));
    String mappings = root.path("mappings").asText("");

    return new ParsedSourceMap(sources, names, decodeMappings(mappings));
  }

  private static List<List<Segment>> decodeMappings(String mappings) {
    ArrayList<List<Segment>> lines = new ArrayList<>();
    if (mappings.isEmpty()) return lines;

    int sourceIndex = 0;
    int sourceLine = 0;
    int sourceColumn = 0;
    int nameIndex = 0;

    // Split on ';' (lines) and ',' (segments). We do a manual walk to avoid allocating
    // intermediate String arrays when the mappings string is large.
    int linesAcc = 1;
    for (int i = 0; i < mappings.length(); i++) if (mappings.charAt(i) == ';') linesAcc++;
    lines.ensureCapacity(linesAcc);

    int generatedColumn;
    int lineStart = 0;
    for (int i = 0; i <= mappings.length(); i++) {
      if (i == mappings.length() || mappings.charAt(i) == ';') {
        // generatedColumn resets for each line per spec.
        generatedColumn = 0;
        List<Segment> lineSegs = new ArrayList<>();
        if (i > lineStart) {
          int segStart = lineStart;
          for (int j = lineStart; j <= i; j++) {
            if (j == i || mappings.charAt(j) == ',') {
              if (j > segStart) {
                Cursor c = new Cursor(mappings.substring(segStart, j));
                generatedColumn += Vlq.decode(c);
                int fields = 1;
                int segSourceIndex = -1;
                int segSourceLine = 0;
                int segSourceColumn = 0;
                int segNameIndex = -1;
                if (c.hasMore()) {
                  sourceIndex += Vlq.decode(c);
                  segSourceIndex = sourceIndex;
                  fields = 4;
                  if (!c.hasMore()) {
                    throw new IllegalArgumentException("mapping with 2 fields is invalid");
                  }
                  sourceLine += Vlq.decode(c);
                  segSourceLine = sourceLine;
                  if (!c.hasMore()) {
                    throw new IllegalArgumentException("mapping with 3 fields is invalid");
                  }
                  sourceColumn += Vlq.decode(c);
                  segSourceColumn = sourceColumn;
                  if (c.hasMore()) {
                    nameIndex += Vlq.decode(c);
                    segNameIndex = nameIndex;
                    fields = 5;
                  }
                }
                if (fields == 1) {
                  lineSegs.add(new Segment(generatedColumn, -1, 0, 0, -1));
                } else {
                  lineSegs.add(
                      new Segment(
                          generatedColumn,
                          segSourceIndex,
                          segSourceLine,
                          segSourceColumn,
                          segNameIndex));
                }
              }
              segStart = j + 1;
            }
          }
        }
        // Spec doesn't mandate sorted segments; sort to make binary-search lookup correct.
        lineSegs.sort((a, b) -> Integer.compare(a.generatedColumn(), b.generatedColumn()));
        lines.add(Collections.unmodifiableList(lineSegs));
        lineStart = i + 1;
      }
    }
    return lines;
  }

  private static List<String> stringArray(JsonNode node) {
    if (node == null || !node.isArray()) return List.of();
    List<String> out = new ArrayList<>(node.size());
    for (JsonNode n : node) out.add(n.asText(""));
    return Collections.unmodifiableList(out);
  }
}
