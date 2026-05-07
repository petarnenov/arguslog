package org.arguslog.api.billing.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import org.arguslog.api.billing.application.UsageUseCase.UsageSnapshot;

/**
 * Read-side projection of a usage snapshot. Pricing exposed in cents so the dashboard formats with
 * i18n-aware currency on the client; retention exposed in days for UI rendering.
 *
 * <p>{@code paymentGraceUntil} is omitted from the JSON when no grace is open (most orgs, most of
 * the time) so existing dashboards that haven't learned the field treat absence as "no banner".
 *
 * <p>{@code billingInterval} is always present (defaults to {@code "monthly"} via the migration);
 * {@code renewsAt} is omitted for free-tier orgs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UsageResponse(
    String plan,
    @JsonProperty("monthlyPriceCents") int monthlyPriceCents,
    @JsonProperty("eventsUsed") long eventsUsed,
    @JsonProperty("eventCap") long eventCap,
    @JsonProperty("projectCap") int projectCap,
    @JsonProperty("retentionDays") long retentionDays,
    double ratio,
    boolean exceeded,
    @JsonProperty("paymentGraceUntil") Instant paymentGraceUntil,
    @JsonProperty("billingInterval") String billingInterval,
    @JsonProperty("renewsAt") Instant renewsAt) {

  public static UsageResponse from(UsageSnapshot s) {
    return new UsageResponse(
        s.plan().dbValue(),
        s.plan().monthlyPriceCents(),
        s.eventsUsed(),
        s.eventCap(),
        s.plan().projectCap(),
        s.plan().retention().toDays(),
        s.ratio(),
        s.exceeded(),
        s.paymentGraceUntil(),
        s.billingInterval().dbValue(),
        s.renewsAt());
  }
}
