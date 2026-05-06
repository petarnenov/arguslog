package org.arguslog.worker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.arguslog.worker.domain.ParsedSourceMap.OriginalLocation;
import org.arguslog.worker.domain.ParsedSourceMap.Segment;
import org.junit.jupiter.api.Test;

class ParsedSourceMapTest {

  private static ParsedSourceMap mapWithLine(List<Segment> segs) {
    return new ParsedSourceMap(List.of("app.js"), List.of("render", "compute"), List.of(segs));
  }

  @Test
  void picksRightmostSegmentWhereGeneratedColumnIsLeqRequested() {
    ParsedSourceMap map =
        mapWithLine(
            List.of(
                new Segment(0, 0, 0, 0, -1),
                new Segment(10, 0, 0, 5, 0),
                new Segment(25, 0, 1, 2, 1)));

    // Column 0 → first segment
    assertThat(map.lookup(0, 0).orElseThrow().column()).isEqualTo(0);
    // Column 9 → still first segment (10 is exclusive)
    assertThat(map.lookup(0, 9).orElseThrow().column()).isEqualTo(0);
    // Column 10 → second segment
    OriginalLocation hit10 = map.lookup(0, 10).orElseThrow();
    assertThat(hit10.column()).isEqualTo(5);
    assertThat(hit10.name()).isEqualTo("render");
    // Column 100 → past everything, falls onto the last segment
    OriginalLocation hit100 = map.lookup(0, 100).orElseThrow();
    assertThat(hit100.line()).isEqualTo(1);
    assertThat(hit100.name()).isEqualTo("compute");
  }

  @Test
  void returnsEmptyWhenLineOutOfRange() {
    ParsedSourceMap map = mapWithLine(List.of(new Segment(0, 0, 0, 0, -1)));
    assertThat(map.lookup(5, 0)).isEmpty();
    assertThat(map.lookup(-1, 0)).isEmpty();
  }

  @Test
  void returnsEmptyWhenColumnPrecedesAnySegment() {
    ParsedSourceMap map = mapWithLine(List.of(new Segment(10, 0, 0, 0, -1)));
    assertThat(map.lookup(0, 5)).isEmpty();
  }

  @Test
  void oneFieldSegmentIsTreatedAsAGap() {
    // Segment with sourceIndex == -1 (1-field mapping) means "no source for this generated col".
    ParsedSourceMap map = mapWithLine(List.of(new Segment(0, -1, 0, 0, -1)));
    assertThat(map.lookup(0, 0)).isEmpty();
  }

  @Test
  void emptyLineReturnsEmpty() {
    ParsedSourceMap map = mapWithLine(List.of());
    assertThat(map.lookup(0, 0)).isEmpty();
  }
}
