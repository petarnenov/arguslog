package org.arguslog.api.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlanTierTest {

  @Test
  void wireValuesMatchTheDbEnum() {
    assertThat(PlanTier.FREE.dbValue()).isEqualTo("free");
    assertThat(PlanTier.STARTER.dbValue()).isEqualTo("starter");
    assertThat(PlanTier.PRO.dbValue()).isEqualTo("pro");
    assertThat(PlanTier.BUSINESS.dbValue()).isEqualTo("business");
    assertThat(PlanTier.ENTERPRISE.dbValue()).isEqualTo("enterprise");
  }

  @Test
  void retentionLadderRisesAcrossPaidTiers() {
    assertThat(PlanTier.FREE.retention().toDays()).isEqualTo(30);
    assertThat(PlanTier.STARTER.retention().toDays()).isEqualTo(30);
    assertThat(PlanTier.PRO.retention().toDays()).isEqualTo(90);
    assertThat(PlanTier.BUSINESS.retention().toDays()).isEqualTo(365);
  }

  @Test
  void monthlyBasePricesMatchPricingDecision() {
    assertThat(PlanTier.FREE.monthlyPriceCents()).isZero();
    assertThat(PlanTier.STARTER.monthlyPriceCents()).isEqualTo(1199);
    assertThat(PlanTier.PRO.monthlyPriceCents()).isEqualTo(2999);
    assertThat(PlanTier.BUSINESS.monthlyPriceCents()).isEqualTo(7999);
    assertThat(PlanTier.ENTERPRISE.monthlyPriceCents()).isZero();
  }

  @Test
  void capsRiseAcrossTiers() {
    assertThat(PlanTier.FREE.monthlyEventCap()).isLessThan(PlanTier.STARTER.monthlyEventCap());
    assertThat(PlanTier.STARTER.monthlyEventCap()).isLessThan(PlanTier.PRO.monthlyEventCap());
    assertThat(PlanTier.PRO.monthlyEventCap()).isLessThan(PlanTier.BUSINESS.monthlyEventCap());

    assertThat(PlanTier.FREE.projectCap()).isLessThan(PlanTier.STARTER.projectCap());
    assertThat(PlanTier.STARTER.projectCap()).isLessThan(PlanTier.PRO.projectCap());
    assertThat(PlanTier.PRO.projectCap()).isLessThan(PlanTier.BUSINESS.projectCap());

    assertThat(PlanTier.FREE.memberCap()).isLessThan(PlanTier.STARTER.memberCap());
    assertThat(PlanTier.STARTER.memberCap()).isLessThan(PlanTier.PRO.memberCap());
    assertThat(PlanTier.PRO.memberCap()).isLessThan(PlanTier.BUSINESS.memberCap());
  }

  @Test
  void starterPriceLadderFollowsAggressiveDiscounts() {
    assertThat(PlanTier.STARTER.priceCentsForDuration(1)).isEqualTo(1199);
    assertThat(PlanTier.STARTER.priceCentsForDuration(3)).isEqualTo(2999);
    assertThat(PlanTier.STARTER.priceCentsForDuration(6)).isEqualTo(5399);
    assertThat(PlanTier.STARTER.priceCentsForDuration(12)).isEqualTo(9599);
  }

  @Test
  void proPriceLadderFollowsAggressiveDiscounts() {
    assertThat(PlanTier.PRO.priceCentsForDuration(1)).isEqualTo(2999);
    assertThat(PlanTier.PRO.priceCentsForDuration(3)).isEqualTo(7499);
    assertThat(PlanTier.PRO.priceCentsForDuration(6)).isEqualTo(13499);
    assertThat(PlanTier.PRO.priceCentsForDuration(12)).isEqualTo(23999);
  }

  @Test
  void businessPriceLadderFollowsAggressiveDiscounts() {
    assertThat(PlanTier.BUSINESS.priceCentsForDuration(1)).isEqualTo(7999);
    assertThat(PlanTier.BUSINESS.priceCentsForDuration(3)).isEqualTo(19999);
    assertThat(PlanTier.BUSINESS.priceCentsForDuration(6)).isEqualTo(35999);
    assertThat(PlanTier.BUSINESS.priceCentsForDuration(12)).isEqualTo(63999);
  }

  @Test
  void priceForDurationRejectsUnsupportedMonths() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> PlanTier.STARTER.priceCentsForDuration(2));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> PlanTier.PRO.priceCentsForDuration(24));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> PlanTier.BUSINESS.priceCentsForDuration(0));
  }

  @Test
  void freeAndEnterprisePriceForAnyDurationIsZero() {
    assertThat(PlanTier.FREE.priceCentsForDuration(1)).isZero();
    assertThat(PlanTier.FREE.priceCentsForDuration(12)).isZero();
    assertThat(PlanTier.ENTERPRISE.priceCentsForDuration(12)).isZero();
  }

  @Test
  void isPaidIdentifiesSelfServeTiers() {
    assertThat(PlanTier.FREE.isPaid()).isFalse();
    assertThat(PlanTier.STARTER.isPaid()).isTrue();
    assertThat(PlanTier.PRO.isPaid()).isTrue();
    assertThat(PlanTier.BUSINESS.isPaid()).isTrue();
    assertThat(PlanTier.ENTERPRISE.isPaid()).isFalse();
  }

  @Test
  void fromDbValueIsCaseInsensitiveAndFallsBackToFree() {
    assertThat(PlanTier.fromDbValue("PRO")).isEqualTo(PlanTier.PRO);
    assertThat(PlanTier.fromDbValue("starter")).isEqualTo(PlanTier.STARTER);
    assertThat(PlanTier.fromDbValue("Business")).isEqualTo(PlanTier.BUSINESS);
    assertThat(PlanTier.fromDbValue("legendary")).isEqualTo(PlanTier.FREE);
    assertThat(PlanTier.fromDbValue(null)).isEqualTo(PlanTier.FREE);
  }
}
