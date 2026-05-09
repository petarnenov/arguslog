package org.arguslog.api.billing.adapter.in.web;

import org.arguslog.api.billing.adapter.in.web.dto.BillingPlansResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated pricing config. Frontend reads this once at page load and renders the
 * checkout cards from the response — bumping a price is a {@code PlanTier} edit + deploy of api,
 * no frontend ship required.
 *
 * <p>Lives outside {@code /api/v1/orgs/{orgId}/...} on purpose: the marketing/pricing page is
 * shown to logged-out visitors too.
 */
@RestController
@RequestMapping(value = "/api/v1/billing/plans", produces = MediaType.APPLICATION_JSON_VALUE)
public class BillingPlansController {

  @GetMapping
  public BillingPlansResponse plans() {
    return BillingPlansResponse.defaults();
  }
}
