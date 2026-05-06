package org.arguslog.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class DsnTest {

  @Test
  void parsesValidHttpsDsn() {
    Dsn dsn = Dsn.parse("https://abc123@ingest.arguslog.io/42");
    assertThat(dsn.publicKey()).isEqualTo("abc123");
    assertThat(dsn.host()).isEqualTo("ingest.arguslog.io");
    assertThat(dsn.projectId()).isEqualTo("42");
    assertThat(dsn.ingestUrl()).isEqualTo("https://ingest.arguslog.io/api/42/events");
  }

  @Test
  void parsesLocalDevDsnWithPort() {
    Dsn dsn = Dsn.parse("http://k@localhost:8080/1");
    assertThat(dsn.ingestUrl()).isEqualTo("http://localhost:8080/api/1/events");
  }

  @Test
  void rejectsInvalidScheme() {
    assertThatIllegalArgumentException().isThrownBy(() -> Dsn.parse("ftp://k@host/1"));
  }

  @Test
  void rejectsMissingProjectId() {
    assertThatIllegalArgumentException().isThrownBy(() -> Dsn.parse("https://k@host/"));
  }
}
