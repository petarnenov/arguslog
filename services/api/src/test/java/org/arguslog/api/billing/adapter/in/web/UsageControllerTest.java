package org.arguslog.api.billing.adapter.in.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.arguslog.api.billing.application.UsageUseCase.UsageSnapshot;
import org.arguslog.api.billing.domain.BillingInterval;
import org.arguslog.api.billing.domain.PlanTier;
import org.arguslog.api.testsupport.AbstractControllerTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class UsageControllerTest extends AbstractControllerTest {

  @Test
  void getReturnsSerializedSnapshot() throws Exception {
    when(usageUseCase.snapshot(1L))
        .thenReturn(
            Optional.of(
                new UsageSnapshot(
                    PlanTier.PRO,
                    25_000L,
                    100_000L,
                    0.25,
                    false,
                    null,
                    BillingInterval.MONTHLY,
                    null)));

    mvc.perform(get("/api/v1/orgs/1/usage").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.plan").value("pro"))
        .andExpect(jsonPath("$.monthlyPriceCents").value(900))
        .andExpect(jsonPath("$.eventsUsed").value(25000))
        .andExpect(jsonPath("$.eventCap").value(100000))
        .andExpect(jsonPath("$.projectCap").value(10))
        .andExpect(jsonPath("$.retentionDays").value(30))
        .andExpect(jsonPath("$.ratio").value(0.25))
        .andExpect(jsonPath("$.exceeded").value(false))
        .andExpect(jsonPath("$.billingInterval").value("monthly"))
        // No grace open → field omitted by @JsonInclude(NON_NULL).
        .andExpect(jsonPath("$.paymentGraceUntil").doesNotExist())
        // Free / never-renewed → renewsAt also omitted.
        .andExpect(jsonPath("$.renewsAt").doesNotExist());
  }

  @Test
  void getExposesAnnualIntervalAndRenewsAtForPrepaidOrgs() throws Exception {
    java.time.Instant renews = java.time.Instant.parse("2027-05-06T00:00:00Z");
    when(usageUseCase.snapshot(1L))
        .thenReturn(
            Optional.of(
                new UsageSnapshot(
                    PlanTier.PRO,
                    1_000L,
                    100_000L,
                    0.01,
                    false,
                    null,
                    BillingInterval.ANNUAL,
                    renews)));

    mvc.perform(get("/api/v1/orgs/1/usage").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.billingInterval").value("annual"))
        .andExpect(jsonPath("$.renewsAt").value("2027-05-06T00:00:00Z"));
  }

  @Test
  void getReturnsGraceWhenSet() throws Exception {
    java.time.Instant grace = java.time.Instant.parse("2026-05-22T14:00:00Z");
    when(usageUseCase.snapshot(1L))
        .thenReturn(
            Optional.of(
                new UsageSnapshot(
                    PlanTier.PRO,
                    100L,
                    100_000L,
                    0.001,
                    false,
                    grace,
                    BillingInterval.MONTHLY,
                    null)));

    mvc.perform(get("/api/v1/orgs/1/usage").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentGraceUntil").value("2026-05-22T14:00:00Z"));
  }

  @Test
  void unknownOrgReturns404() throws Exception {
    when(usageUseCase.snapshot(eq(99L))).thenReturn(Optional.empty());
    mvc.perform(get("/api/v1/orgs/99/usage")).andExpect(status().isNotFound());
  }
}
