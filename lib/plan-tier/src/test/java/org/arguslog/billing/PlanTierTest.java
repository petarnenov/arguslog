package org.arguslog.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlanTierTest {

  @Test
  void wireValuesMatchTheDbEnum() {
    assertThat(PlanTier.REGULAR.dbValue()).isEqualTo("regular");
    assertThat(PlanTier.SILVER.dbValue()).isEqualTo("silver");
    assertThat(PlanTier.GOLD.dbValue()).isEqualTo("gold");
    assertThat(PlanTier.PLATINUM.dbValue()).isEqualTo("platinum");
  }

  @Test
  void retentionLadderRisesAcrossTiers() {
    assertThat(PlanTier.REGULAR.retention().toDays()).isEqualTo(30);
    assertThat(PlanTier.SILVER.retention().toDays()).isEqualTo(30);
    assertThat(PlanTier.GOLD.retention().toDays()).isEqualTo(90);
    assertThat(PlanTier.PLATINUM.retention().toDays()).isEqualTo(365);
  }

  @Test
  void capsRiseAcrossTiers() {
    assertThat(PlanTier.REGULAR.monthlyEventCap()).isLessThan(PlanTier.SILVER.monthlyEventCap());
    assertThat(PlanTier.SILVER.monthlyEventCap()).isLessThan(PlanTier.GOLD.monthlyEventCap());
    assertThat(PlanTier.GOLD.monthlyEventCap()).isLessThan(PlanTier.PLATINUM.monthlyEventCap());

    assertThat(PlanTier.REGULAR.projectCap()).isLessThan(PlanTier.SILVER.projectCap());
    assertThat(PlanTier.SILVER.projectCap()).isLessThan(PlanTier.GOLD.projectCap());
    assertThat(PlanTier.GOLD.projectCap()).isLessThan(PlanTier.PLATINUM.projectCap());

    assertThat(PlanTier.REGULAR.memberCap()).isLessThan(PlanTier.SILVER.memberCap());
    assertThat(PlanTier.SILVER.memberCap()).isLessThan(PlanTier.GOLD.memberCap());
    assertThat(PlanTier.GOLD.memberCap()).isLessThan(PlanTier.PLATINUM.memberCap());
  }

  @Test
  void fromDbValueIsCaseInsensitiveAndFallsBackToRegular() {
    assertThat(PlanTier.fromDbValue("GOLD")).isEqualTo(PlanTier.GOLD);
    assertThat(PlanTier.fromDbValue("silver")).isEqualTo(PlanTier.SILVER);
    assertThat(PlanTier.fromDbValue("Platinum")).isEqualTo(PlanTier.PLATINUM);
    assertThat(PlanTier.fromDbValue("legendary")).isEqualTo(PlanTier.REGULAR);
    assertThat(PlanTier.fromDbValue(null)).isEqualTo(PlanTier.REGULAR);
  }
}
