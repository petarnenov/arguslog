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
  void proBaseIsElevenNinetyNineInCents() {
    assertThat(PlanTier.PRO.monthlyPriceCents()).isEqualTo(1199);
  }

  @Test
  void freeAndEnterpriseHaveZeroDirectPrice() {
    assertThat(PlanTier.FREE.monthlyPriceCents()).isZero();
    assertThat(PlanTier.ENTERPRISE.monthlyPriceCents()).isZero();
  }

  @Test
  void proPriceLadderFollowsAggressiveDiscounts() {
    assertThat(PlanTier.PRO.priceCentsForDuration(1)).isEqualTo(1199);
    assertThat(PlanTier.PRO.priceCentsForDuration(3)).isEqualTo(2999);
    assertThat(PlanTier.PRO.priceCentsForDuration(6)).isEqualTo(5399);
    assertThat(PlanTier.PRO.priceCentsForDuration(12)).isEqualTo(9599);
  }

  @Test
  void priceForDurationRejectsUnsupportedMonths() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> PlanTier.PRO.priceCentsForDuration(2));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> PlanTier.PRO.priceCentsForDuration(24));
  }

  @Test
  void freeAndEnterprisePriceForAnyDurationIsZero() {
    assertThat(PlanTier.FREE.priceCentsForDuration(1)).isZero();
    assertThat(PlanTier.ENTERPRISE.priceCentsForDuration(12)).isZero();
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
