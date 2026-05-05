package dev.argus.worker.adapter.out.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.argus.worker.domain.Fingerprint;
import org.junit.jupiter.api.Test;

class PayloadFingerprinterTest {

  private final PayloadFingerprinter fp = new PayloadFingerprinter(new ObjectMapper());

  @Test
  void exceptionTypeAndValueDriveTheGroup() {
    Fingerprint a =
        fp.compute(
            json(
                """
        {"level":"error","exception":{"values":[{"type":"TypeError","value":"x is undefined"}]}}
        """));
    Fingerprint b =
        fp.compute(
            json(
                """
        {"level":"error","exception":{"values":[{"type":"TypeError","value":"x is undefined"}]}}
        """));
    Fingerprint c =
        fp.compute(
            json(
                """
        {"level":"error","exception":{"values":[{"type":"TypeError","value":"y is undefined"}]}}
        """));

    assertThat(a.hash()).isEqualTo(b.hash()).isNotEqualTo(c.hash());
    assertThat(a.title()).isEqualTo("TypeError: x is undefined");
    assertThat(a.level()).isEqualTo(Fingerprint.Level.ERROR);
  }

  @Test
  void differentExceptionTypesProduceDifferentGroups() {
    Fingerprint typeError =
        fp.compute(json("{\"exception\":{\"values\":[{\"type\":\"TypeError\",\"value\":\"x\"}]}}"));
    Fingerprint refError =
        fp.compute(
            json("{\"exception\":{\"values\":[{\"type\":\"ReferenceError\",\"value\":\"x\"}]}}"));
    assertThat(typeError.hash()).isNotEqualTo(refError.hash());
  }

  @Test
  void messageBecomesGroupWhenNoException() {
    Fingerprint a =
        fp.compute(json("{\"level\":\"warning\",\"message\":\"ConfigError: bad port\"}"));
    Fingerprint b =
        fp.compute(json("{\"level\":\"warning\",\"message\":\"ConfigError: bad port\"}"));
    assertThat(a.hash()).isEqualTo(b.hash());
    assertThat(a.title()).isEqualTo("ConfigError: bad port");
    assertThat(a.level()).isEqualTo(Fingerprint.Level.WARNING);
  }

  @Test
  void exceptionAndMessageGroupsDoNotCollide() {
    Fingerprint exception =
        fp.compute(json("{\"exception\":{\"values\":[{\"type\":\"E\",\"value\":\"hi\"}]}}"));
    Fingerprint message = fp.compute(json("{\"message\":\"E|hi\"}"));
    assertThat(exception.hash()).isNotEqualTo(message.hash());
  }

  @Test
  void unparseableOrEmptyPayloadFallsBackToUnknown() {
    assertThat(fp.compute(null).hash()).isEqualTo(PayloadFingerprinter.UNKNOWN_HASH);
    assertThat(fp.compute("").hash()).isEqualTo(PayloadFingerprinter.UNKNOWN_HASH);
    assertThat(fp.compute("not-json").hash()).isEqualTo(PayloadFingerprinter.UNKNOWN_HASH);
    assertThat(fp.compute("{}").hash()).isEqualTo(PayloadFingerprinter.UNKNOWN_HASH);
  }

  @Test
  void culpritIsTopFrame() {
    Fingerprint f =
        fp.compute(
            json(
                """
                {"exception":{"values":[{"type":"E","value":"x","stacktrace":{"frames":[
                  {"filename":"a.js","function":"old","lineno":1},
                  {"filename":"b.js","function":"recent","lineno":42}
                ]}}]}}
                """));
    assertThat(f.culprit()).isEqualTo("recent at b.js:42");
  }

  @Test
  void titleIsTrimmedTo200Chars() {
    String longValue = "x".repeat(500);
    Fingerprint f =
        fp.compute(
            json(
                "{\"exception\":{\"values\":[{\"type\":\"E\",\"value\":\"" + longValue + "\"}]}}"));
    assertThat(f.title()).hasSize(200);
  }

  private static String json(String s) {
    return s;
  }
}
