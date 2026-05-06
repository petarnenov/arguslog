package org.arguslog.worker.adapter.out.sourcemap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.arguslog.worker.adapter.out.sourcemap.Vlq.Cursor;
import org.junit.jupiter.api.Test;

class VlqTest {

  @Test
  void decodesSingleCharValuesWithoutContinuation() {
    // base64 'A'=0, 'C'=2, 'D'=3, 'E'=4, 'F'=5
    // Zigzag: 0→0, 1→2, -1→3, 2→4, -2→5
    assertThat(decode("A")).isEqualTo(0);
    assertThat(decode("C")).isEqualTo(1);
    assertThat(decode("D")).isEqualTo(-1);
    assertThat(decode("E")).isEqualTo(2);
    assertThat(decode("F")).isEqualTo(-2);
  }

  @Test
  void decodesMultiCharValuesWithContinuation() {
    // 16 → zigzag 32 → 0b100000 → chunk0 00000+cont = 32 = 'g', chunk1 1 = 'B'
    assertThat(decode("gB")).isEqualTo(16);
    // -16 → zigzag 33 → chunk0 00001+cont = 33 = 'h', chunk1 1 = 'B'
    assertThat(decode("hB")).isEqualTo(-16);
    // 100 → zigzag 200 → chunk0 01000+cont = 40 = 'o', chunk1 6 = 'G'
    assertThat(decode("oG")).isEqualTo(100);
  }

  @Test
  void readsConsecutiveValuesFromOneCursor() {
    // Decode sequence 0, 1, -1
    Cursor c = new Cursor("ACD");
    assertThat(Vlq.decode(c)).isEqualTo(0);
    assertThat(Vlq.decode(c)).isEqualTo(1);
    assertThat(Vlq.decode(c)).isEqualTo(-1);
    assertThat(c.hasMore()).isFalse();
  }

  @Test
  void truncatedContinuationThrows() {
    // 'g' alone has the continuation bit set with no follow-up — malformed.
    assertThatThrownBy(() -> Vlq.decode(new Cursor("g")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("truncated");
  }

  @Test
  void invalidBase64CharThrows() {
    assertThatThrownBy(() -> Vlq.decode(new Cursor("@")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid base64");
  }

  private static int decode(String segment) {
    return Vlq.decode(new Cursor(segment));
  }
}
