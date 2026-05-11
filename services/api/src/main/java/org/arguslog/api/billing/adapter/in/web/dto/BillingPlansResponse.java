package org.arguslog.api.billing.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import org.arguslog.billing.PlanTier;

/**
 * Server-driven pricing config consumed by the public billing page. Frontend reads this once on
 * page load and renders the tier cards from {@code tiers[]}; pricing changes therefore require
 * no frontend deploy.
 *
 * <p>The response includes Free as a non-purchasable info row (durations is empty, prices are 0)
 * so the UI can render its caps next to the paid tiers — reads as a comparison column rather
 * than a CTA.
 */
public record BillingPlansResponse(
    @JsonProperty("currency") String currency, @JsonProperty("tiers") List<TierInfo> tiers) {

  public record TierInfo(
      @JsonProperty("plan") String plan,
      @JsonProperty("monthlyPriceCents") int monthlyPriceCents,
      @JsonProperty("monthlyEventCap") long monthlyEventCap,
      @JsonProperty("projectCap") int projectCap,
      @JsonProperty("memberCap") int memberCap,
      @JsonProperty("orgCap") int orgCap,
      @JsonProperty("retentionDays") long retentionDays,
      @JsonProperty("unlimitedProjects") boolean unlimitedProjects,
      @JsonProperty("unlimitedMembers") boolean unlimitedMembers,
      @JsonProperty("unlimitedOrgs") boolean unlimitedOrgs,
      @JsonProperty("unlimitedEvents") boolean unlimitedEvents,
      @JsonProperty("durations") List<DurationOffer> durations) {}

  public record DurationOffer(
      @JsonProperty("months") int months,
      @JsonProperty("amountCents") int amountCents,
      @JsonProperty("perMonthCents") int perMonthCents,
      @JsonProperty("savePercent") int savePercent) {}

  public static BillingPlansResponse defaults() {
    List<TierInfo> tiers = new ArrayList<>();
    for (PlanTier tier : List.of(PlanTier.FREE, PlanTier.STARTER, PlanTier.PRO, PlanTier.BUSINESS)) {
      tiers.add(toTierInfo(tier));
    }
    return new BillingPlansResponse("USD", tiers);
  }

  private static TierInfo toTierInfo(PlanTier tier) {
    return new TierInfo(
        tier.dbValue(),
        tier.monthlyPriceCents(),
        tier.monthlyEventCap(),
        tier.projectCap(),
        tier.memberCap(),
        tier.orgCap(),
        tier.retention().toDays(),
        tier.projectCap() == Integer.MAX_VALUE,
        tier.memberCap() == Integer.MAX_VALUE,
        tier.orgCap() == Integer.MAX_VALUE,
        tier.monthlyEventCap() == Long.MAX_VALUE,
        offersFor(tier));
  }

  private static List<DurationOffer> offersFor(PlanTier tier) {
    if (!tier.isPaid()) return List.of();
    int baseMonthly = tier.priceCentsForDuration(1);
    return List.of(
        offer(tier, 1, baseMonthly),
        offer(tier, 3, baseMonthly),
        offer(tier, 6, baseMonthly),
        offer(tier, 12, baseMonthly));
  }

  private static DurationOffer offer(PlanTier tier, int months, int baseMonthlyCents) {
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
