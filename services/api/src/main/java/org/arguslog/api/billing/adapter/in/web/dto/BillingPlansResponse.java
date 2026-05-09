package org.arguslog.api.billing.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.arguslog.api.billing.domain.PlanTier;

/**
 * Server-driven pricing config consumed by the public billing page. Frontend reads this once on
 * page load and renders the four duration cards from {@code pro.durations[]}; pricing changes
 * therefore require no frontend deploy.
 */
public record BillingPlansResponse(
    @JsonProperty("currency") String currency,
    @JsonProperty("free") TierInfo free,
    @JsonProperty("pro") TierInfo pro,
    @JsonProperty("enterprise") TierInfo enterprise) {

  public record TierInfo(
      @JsonProperty("plan") String plan,
      @JsonProperty("monthlyEventCap") long monthlyEventCap,
      @JsonProperty("projectCap") int projectCap,
      @JsonProperty("retentionDays") long retentionDays,
      @JsonProperty("durations") List<DurationOffer> durations) {}

  public record DurationOffer(
      @JsonProperty("months") int months,
      @JsonProperty("amountCents") int amountCents,
      @JsonProperty("perMonthCents") int perMonthCents,
      @JsonProperty("savePercent") int savePercent) {}

  public static BillingPlansResponse defaults() {
    int baseMonthly = PlanTier.PRO.priceCentsForDuration(1);
    List<DurationOffer> proOffers =
        List.of(
            offerFor(PlanTier.PRO, 1, baseMonthly),
            offerFor(PlanTier.PRO, 3, baseMonthly),
            offerFor(PlanTier.PRO, 6, baseMonthly),
            offerFor(PlanTier.PRO, 12, baseMonthly));

    return new BillingPlansResponse(
        "USD",
        new TierInfo(
            PlanTier.FREE.dbValue(),
            PlanTier.FREE.monthlyEventCap(),
            PlanTier.FREE.projectCap(),
            PlanTier.FREE.retention().toDays(),
            List.of()),
        new TierInfo(
            PlanTier.PRO.dbValue(),
            PlanTier.PRO.monthlyEventCap(),
            PlanTier.PRO.projectCap(),
            PlanTier.PRO.retention().toDays(),
            proOffers),
        new TierInfo(
            PlanTier.ENTERPRISE.dbValue(),
            PlanTier.ENTERPRISE.monthlyEventCap(),
            PlanTier.ENTERPRISE.projectCap(),
            PlanTier.ENTERPRISE.retention().toDays(),
            List.of()));
  }

  private static DurationOffer offerFor(PlanTier tier, int months, int baseMonthlyCents) {
    int totalCents = tier.priceCentsForDuration(months);
    int perMonthCents = totalCents / months;
    int undiscountedCents = baseMonthlyCents * months;
    int savePercent =
        undiscountedCents == 0
            ? 0
            : Math.round(((undiscountedCents - totalCents) * 100f) / undiscountedCents);
    return new DurationOffer(months, totalCents, perMonthCents, savePercent);
  }
}
