package org.arguslog.worker.domain;

import java.util.List;
import java.util.Optional;

/**
 * In-memory representation of a parsed sourcemap v3 ready for `(generatedLine, generatedColumn) →
 * original` lookups. Per-line segment lists are kept sorted by generated column so lookup is a
 * binary search.
 *
 * <p>{@code lines.get(line)} returns the segments for that 0-based generated line. Within a line we
 * pick the segment with the largest {@code generatedColumn ≤ requested}; that's the standard
 * sourcemap-v3 lookup rule (a single mapping covers a span of generated columns).
 */
public record ParsedSourceMap(List<String> sources, List<String> names, List<List<Segment>> lines) {

  public Optional<OriginalLocation> lookup(int generatedLine, int generatedColumn) {
    if (generatedLine < 0 || generatedLine >= lines.size()) return Optional.empty();
    List<Segment> lineSegs = lines.get(generatedLine);
    if (lineSegs.isEmpty()) return Optional.empty();

    // Binary search for the rightmost segment with generatedColumn ≤ requested.
    int lo = 0;
    int hi = lineSegs.size() - 1;
    int found = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (lineSegs.get(mid).generatedColumn <= generatedColumn) {
        found = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (found < 0) return Optional.empty();
    Segment seg = lineSegs.get(found);
    if (seg.sourceIndex < 0) return Optional.empty(); // 1-arg segment — generated col only
    String source = seg.sourceIndex < sources.size() ? sources.get(seg.sourceIndex) : null;
    String name =
        seg.nameIndex >= 0 && seg.nameIndex < names.size() ? names.get(seg.nameIndex) : null;
    return Optional.of(new OriginalLocation(source, seg.sourceLine, seg.sourceColumn, name));
  }

  /**
   * One mappings segment. {@code sourceIndex} / {@code nameIndex} are -1 when the producer wrote a
   * 1-field segment (no source mapping for this generated column).
   */
  public record Segment(
      int generatedColumn, int sourceIndex, int sourceLine, int sourceColumn, int nameIndex) {}

  public record OriginalLocation(String source, int line, int column, String name) {}
}
