package org.arguslog.api.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlanTierTest {

  @Test
  void wireValuesMatchTheDbEnum() {
    assertThat(PlanTier.FREE.dbValue()).isEqualTo("free");
    assertThat(PlanTier.PRO.dbValue()).isEqualTo("pro");
    assertThat(PlanTier.ENTERPRISE.dbValue()).isEqualTo("enterprise");
  }

  @Test
  void freeAndProShareThirtyDayRetention() {
    assertThat(PlanTier.FREE.retention().toDays()).isEqualTo(30);
    assertThat(PlanTier.PRO.retention().toDays()).isEqualTo(30);
  }

  @Test
  void proIsNineDollarsInCents() {
    assertThat(PlanTier.PRO.monthlyPriceCents()).isEqualTo(900);
  }

  @Test
  void freeAndEnterpriseHaveZeroDirectPrice() {
    assertThat(PlanTier.FREE.monthlyPriceCents()).isZero();
    assertThat(PlanTier.ENTERPRISE.monthlyPriceCents()).isZero();
  }

  @Test
  void capsRiseAcrossTiers() {
    assertThat(PlanTier.FREE.monthlyEventCap()).isLessThan(PlanTier.PRO.monthlyEventCap());
    assertThat(PlanTier.PRO.monthlyEventCap()).isLessThan(PlanTier.ENTERPRISE.monthlyEventCap());
    assertThat(PlanTier.FREE.projectCap()).isLessThan(PlanTier.PRO.projectCap());
  }

  @Test
  void fromDbValueIsCaseInsensitiveAndFallsBackToFree() {
    assertThat(PlanTier.fromDbValue("PRO")).isEqualTo(PlanTier.PRO);
    assertThat(PlanTier.fromDbValue("free")).isEqualTo(PlanTier.FREE);
    // Unknown wire string → safest default (most restrictive).
    assertThat(PlanTier.fromDbValue("legendary")).isEqualTo(PlanTier.FREE);
    assertThat(PlanTier.fromDbValue(null)).isEqualTo(PlanTier.FREE);
  }
}
