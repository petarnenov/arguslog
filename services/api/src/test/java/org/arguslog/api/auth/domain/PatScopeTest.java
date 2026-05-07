package org.arguslog.api.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PatScopeTest {

  @Test
  void wireFormFollowsResourceColonAction() {
    // Loud assertion so a refactor that changes wire strings is caught — these are persisted in
    // the DB AND embedded in tokens that are already in users' hands.
    assertThat(PatScope.RELEASES_WRITE.wire()).isEqualTo("releases:write");
    assertThat(PatScope.SOURCEMAPS_WRITE.wire()).isEqualTo("sourcemaps:write");
    assertThat(PatScope.ALERTS_READ.wire()).isEqualTo("alerts:read");
  }

  @Test
  void authorityIsSpringSecurityScopePrefixed() {
    assertThat(PatScope.RELEASES_WRITE.authority()).isEqualTo("SCOPE_releases:write");
  }

  @Test
  void fromWireRoundTripsForEveryEnumValue() {
    for (PatScope scope : PatScope.values()) {
      assertThat(PatScope.fromWire(scope.wire())).isEqualTo(scope);
    }
  }

  @Test
  void fromWireIsCaseInsensitiveAndTrims() {
    assertThat(PatScope.fromWire("  RELEASES:WRITE  ")).isEqualTo(PatScope.RELEASES_WRITE);
  }

  @Test
  void fromWireRejectsUnknownScope() {
    assertThatThrownBy(() -> PatScope.fromWire("galaxy:nuke"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown scope");
  }

  @Test
  void allReturnsEveryEnumValue() {
    assertThat(PatScope.all()).containsExactlyInAnyOrder(PatScope.values());
  }
}
