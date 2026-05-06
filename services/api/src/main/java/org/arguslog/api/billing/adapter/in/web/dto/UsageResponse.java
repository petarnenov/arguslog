package org.arguslog.api.billing.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.arguslog.api.billing.application.UsageUseCase.UsageSnapshot;

/**
 * Read-side projection of a usage snapshot. Pricing exposed in cents so the dashboard formats with
 * i18n-aware currency on the client; retention exposed in days for UI rendering.
 */
public record UsageResponse(
    String plan,
    @JsonProperty("monthlyPriceCents") int monthlyPriceCents,
    @JsonProperty("eventsUsed") long eventsUsed,
    @JsonProperty("eventCap") long eventCap,
    @JsonProperty("projectCap") int projectCap,
    @JsonProperty("retentionDays") long retentionDays,
    double ratio,
    boolean exceeded) {

  public static UsageResponse from(UsageSnapshot s) {
    return new UsageResponse(
        s.plan().dbValue(),
        s.plan().monthlyPriceCents(),
        s.eventsUsed(),
        s.eventCap(),
        s.plan().projectCap(),
        s.plan().retention().toDays(),
        s.ratio(),
        s.exceeded());
  }
}
