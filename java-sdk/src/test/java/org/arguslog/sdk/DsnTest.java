package org.arguslog.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class DsnTest {

  @Test
  void parsesProductionDsnAsHttps() {
    Dsn dsn = Dsn.parse("arguslog://abc123@ingest.arguslog.io/api/42");
    assertThat(dsn.publicKey()).isEqualTo("abc123");
    assertThat(dsn.host()).isEqualTo("ingest.arguslog.io");
    assertThat(dsn.projectId()).isEqualTo("42");
    assertThat(dsn.scheme()).isEqualTo("https");
    assertThat(dsn.ingestUrl()).isEqualTo("https://ingest.arguslog.io/api/42/events");
  }

  @Test
  void parsesLoopbackDsnAsHttp() {
    Dsn dsn = Dsn.parse("arguslog://k@localhost:8080/api/1");
    assertThat(dsn.scheme()).isEqualTo("http");
    assertThat(dsn.ingestUrl()).isEqualTo("http://localhost:8080/api/1/events");
  }

  @Test
  void parses127LoopbackAsHttp() {
    Dsn dsn = Dsn.parse("arguslog://k@127.0.0.1:9000/api/7");
    assertThat(dsn.scheme()).isEqualTo("http");
    assertThat(dsn.ingestUrl()).isEqualTo("http://127.0.0.1:9000/api/7/events");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "arguslog://k@192.168.0.186:8080/api/1",
        "arguslog://k@192.168.1.1:8080/api/1",
        "arguslog://k@10.0.0.5:8080/api/1",
        "arguslog://k@10.255.255.255:8080/api/1",
        "arguslog://k@172.16.0.1:8080/api/1",
        "arguslog://k@172.31.255.255:8080/api/1"
      })
  void parsesRfc1918PrivateAsHttp(String dsn) {
    assertThat(Dsn.parse(dsn).scheme()).isEqualTo("http");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "arguslog://k@172.15.0.1:8080/api/1",
        "arguslog://k@172.32.0.1:8080/api/1",
        "arguslog://k@193.168.0.1:8080/api/1",
        "arguslog://k@11.0.0.1:8080/api/1"
      })
  void keepsHttpsForJustOutsideRfc1918(String dsn) {
    assertThat(Dsn.parse(dsn).scheme()).isEqualTo("https");
  }

  @Test
  void rejectsLegacyHttpsScheme() {
    assertThatIllegalArgumentException().isThrownBy(() -> Dsn.parse("https://k@host/1"));
  }

  @Test
  void rejectsMissingApiSegment() {
    assertThatIllegalArgumentException().isThrownBy(() -> Dsn.parse("arguslog://k@host/1"));
  }

  @Test
  void rejectsMissingProjectId() {
    assertThatIllegalArgumentException().isThrownBy(() -> Dsn.parse("arguslog://k@host/api/"));
  }

  @Test
  void rejectsMissingPublicKey() {
    assertThatIllegalArgumentException().isThrownBy(() -> Dsn.parse("arguslog://@host/api/1"));
  }
}
