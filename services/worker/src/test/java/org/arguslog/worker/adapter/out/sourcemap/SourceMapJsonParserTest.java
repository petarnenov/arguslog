package org.arguslog.worker.adapter.out.sourcemap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arguslog.worker.domain.ParsedSourceMap;
import org.arguslog.worker.domain.ParsedSourceMap.OriginalLocation;
import org.junit.jupiter.api.Test;

class SourceMapJsonParserTest {

  private final SourceMapJsonParser parser = new SourceMapJsonParser(new ObjectMapper());

  @Test
  void parsesSourcesNamesAndDecodesMappingsToSegments() {
    // Hand-crafted v3 sourcemap. Mappings string "AAAA;AACA" means:
    //   line 0: one segment at genCol=0 → source[0], srcLine=0, srcCol=0
    //   line 1: one segment at genCol=0 → source[0], srcLine=1, srcCol=0  (delta +1 line)
    String json =
        """
        {
          "version": 3,
          "sources": ["app.js"],
          "names": [],
          "mappings": "AAAA;AACA"
        }
        """;

    ParsedSourceMap map = parser.parse(json);

    assertThat(map.sources()).containsExactly("app.js");
    assertThat(map.lines()).hasSize(2);

    OriginalLocation l0 = map.lookup(0, 0).orElseThrow();
    assertThat(l0.source()).isEqualTo("app.js");
    assertThat(l0.line()).isEqualTo(0);
    assertThat(l0.column()).isEqualTo(0);

    OriginalLocation l1 = map.lookup(1, 0).orElseThrow();
    assertThat(l1.line()).isEqualTo(1);
  }

  @Test
  void capturesNameIndexFromFiveFieldSegments() {
    // "AAAAA" — fifth field is the name delta. Sources=["a"], names=["render"].
    String json =
        """
        {"version":3,"sources":["a.js"],"names":["render"],"mappings":"AAAAA"}
        """;
    ParsedSourceMap map = parser.parse(json);
    OriginalLocation hit = map.lookup(0, 0).orElseThrow();
    assertThat(hit.name()).isEqualTo("render");
  }

  @Test
  void rejectsUnsupportedVersions() {
    assertThatThrownBy(() -> parser.parse("{\"version\":2}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
  }

  @Test
  void rejectsIndexedSourcemaps() {
    assertThatThrownBy(() -> parser.parse("{\"version\":3,\"sections\":[]}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sectioned");
  }

  @Test
  void emptyMappingsYieldsZeroLines() {
    ParsedSourceMap map =
        parser.parse("{\"version\":3,\"sources\":[],\"names\":[],\"mappings\":\"\"}");
    assertThat(map.lines()).isEmpty();
  }

  @Test
  void invalidJsonThrows() {
    assertThatThrownBy(() -> parser.parse("not-json")).isInstanceOf(IllegalArgumentException.class);
  }
}
