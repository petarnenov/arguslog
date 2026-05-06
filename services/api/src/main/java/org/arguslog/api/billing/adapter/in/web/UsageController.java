package org.arguslog.api.billing.adapter.in.web;

import org.arguslog.api.billing.adapter.in.web.dto.UsageResponse;
import org.arguslog.api.billing.application.UsageUseCase;
import org.arguslog.api.security.AccessException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only billing snapshot. Lives under {@code /api/v1/orgs/{orgId}/...} so it picks up the
 * existing {@code OrgAccessGuard} interceptor — only members of {@code orgId} can read.
 *
 * <p>Polled by the dashboard's BillingPage and the cross-page "quota exceeded" banner; fast path so
 * a once-per-minute poll is cheap.
 */
@RestController
@RequestMapping(value = "/api/v1/orgs/{orgId}/usage", produces = MediaType.APPLICATION_JSON_VALUE)
public class UsageController {

  private final UsageUseCase useCase;

  public UsageController(UsageUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public UsageResponse current(@PathVariable long orgId) {
    return useCase
        .snapshot(orgId)
        .map(UsageResponse::from)
        .orElseThrow(() -> AccessException.notFound(orgId));
  }
}
