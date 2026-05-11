package org.arguslog.api.billing.adapter.in.web;

import java.net.URI;
import java.util.Locale;
import org.arguslog.api.billing.adapter.in.web.dto.CryptoCheckoutResponse;
import org.arguslog.api.billing.application.CryptoCheckoutFailedException;
import org.arguslog.api.billing.application.CryptoCheckoutNotConfiguredException;
import org.arguslog.api.billing.application.CryptoCheckoutUseCase;
import org.arguslog.api.billing.application.CryptoCheckoutUseCase.CheckoutResult;
import org.arguslog.billing.PlanTier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * NOWPayments-hosted crypto checkout. Mints an invoice for {@code orgId} buying {@code duration}
 * months of PRO and returns the checkout URL the dashboard opens in a new tab. Membership in the
 * org is enforced upstream by {@code OrgAccessGuard} via the path prefix.
 *
 * <p>The flow is fire-and-forget from the dashboard's POV: the user pays at the NOWPayments
 * page, NOWPayments hits {@code /api/v1/webhooks/nowpayments} server-to-server, and the org's
 * plan flips to Pro on a successful IPN. The dashboard polls {@code /usage} after the redirect
 * to detect the upgrade.
 */
@RestController
@RequestMapping(
    value = "/api/v1/orgs/{orgId}/billing/crypto-invoice",
    produces = MediaType.APPLICATION_JSON_VALUE)
public class CryptoCheckoutController {

  private static final java.util.Set<Integer> ALLOWED_DURATIONS = java.util.Set.of(1, 3, 6, 12);

  private final CryptoCheckoutUseCase useCase;

  public CryptoCheckoutController(CryptoCheckoutUseCase useCase) {
    this.useCase = useCase;
  }

  @PostMapping
  public CryptoCheckoutResponse start(
      @PathVariable long orgId,
      @RequestParam(name = "tier", required = false, defaultValue = "pro") String tier,
      @RequestParam(name = "duration", required = false, defaultValue = "1") int duration) {
    if (!ALLOWED_DURATIONS.contains(duration)) {
      throw new InvalidDurationException(
          "Unsupported duration: " + duration + " months. Allowed: 1, 3, 6, 12.");
    }
    PlanTier planTier = parseTier(tier);
    CheckoutResult result = useCase.start(orgId, planTier, duration);
    return new CryptoCheckoutResponse(result.checkoutUrl(), result.invoiceReference());
  }

  private static PlanTier parseTier(String raw) {
    if (raw == null) {
      throw new InvalidTierException("Tier parameter is required.");
    }
    PlanTier tier;
    try {
      tier = PlanTier.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidTierException(
          "Unknown tier: " + raw + ". Allowed: starter, pro, business.");
    }
    if (!tier.isPaid()) {
      throw new InvalidTierException(
          "Tier "
              + tier.dbValue()
              + " is not sold via the self-serve flow. Allowed: starter, pro, business.");
    }
    return tier;
  }

  static final class InvalidTierException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidTierException(String message) {
      super(message);
    }
  }

  @ExceptionHandler(InvalidTierException.class)
  ResponseEntity<ProblemDetail> handleInvalidTier(InvalidTierException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid tier");
    body.setType(URI.create("https://arguslog.org/problems/invalid-tier"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  static final class InvalidDurationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidDurationException(String message) {
      super(message);
    }
  }

  @ExceptionHandler(InvalidDurationException.class)
  ResponseEntity<ProblemDetail> handleInvalidDuration(InvalidDurationException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid duration");
    body.setType(URI.create("https://arguslog.org/problems/invalid-duration"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(CryptoCheckoutNotConfiguredException.class)
  ResponseEntity<ProblemDetail> handleNotConfigured(CryptoCheckoutNotConfiguredException e) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    body.setTitle("Crypto checkout not configured");
    body.setType(URI.create("https://arguslog.org/problems/crypto-not-configured"));
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(CryptoCheckoutFailedException.class)
  ResponseEntity<ProblemDetail> handleFailed(CryptoCheckoutFailedException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    body.setTitle("Crypto checkout failed");
    body.setType(URI.create("https://arguslog.org/problems/crypto-checkout-failed"));
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
