package org.arguslog.api.adapter.in.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DsnResponseTest {

  @Test
  void formatsDsnStrippingScheme() {
    assertThat(DsnResponse.formatDsn("ABC123", "http://localhost:8080", 42))
        .isEqualTo("argus://ABC123@localhost:8080/api/42");
  }

  @Test
  void formatsDsnWhenHostHasNoScheme() {
    assertThat(DsnResponse.formatDsn("KEY", "argus.example.com", 7))
        .isEqualTo("argus://KEY@argus.example.com/api/7");
  }

  @Test
  void formatsDsnWithHttpsScheme() {
    assertThat(DsnResponse.formatDsn("PK", "https://ingest.argus.example", 99))
        .isEqualTo("argus://PK@ingest.argus.example/api/99");
  }
}
