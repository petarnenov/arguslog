package org.arguslog.api.billing.adapter.in.web;

import java.net.URI;
import org.arguslog.api.billing.adapter.in.web.dto.CheckoutResponse;
import org.arguslog.api.billing.application.CheckoutUseCase;
import org.arguslog.api.billing.application.CheckoutUseCase.CheckoutFailedException;
import org.arguslog.api.billing.application.CheckoutUseCase.StripeNotConfiguredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe Checkout entry point. Single endpoint — POST starts a hosted Checkout Session.
 *
 * <p>Membership is enforced by {@code OrgAccessGuard} via the {@code /orgs/{orgId}/...} path. We
 * intentionally do NOT restrict to org owners for P4: Stripe Checkout itself requires a payment
 * method, so a member-but-not-owner can't accidentally charge anyone — they'd get the form, stall
 * at payment. Granular role checks land in P5 alongside scoped PATs.
 */
@RestController
@RequestMapping(value = "/api/v1/orgs/{orgId}/billing", produces = MediaType.APPLICATION_JSON_VALUE)
public class CheckoutController {

  private final CheckoutUseCase useCase;

  public CheckoutController(CheckoutUseCase useCase) {
    this.useCase = useCase;
  }

  @PostMapping("/checkout-session")
  public CheckoutResponse start(@PathVariable long orgId) {
    return new CheckoutResponse(useCase.createCheckoutUrl(orgId, currentUserEmail()));
  }

  /**
   * Best-effort email lookup from the JWT. Returns null when missing — Stripe accepts a session
   * without a customer email (user fills it in at the form), so a missing claim isn't fatal.
   */
  private static String currentUserEmail() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwtAuth)) return null;
    Jwt jwt = jwtAuth.getToken();
    Object email = jwt.getClaim("email");
    return email instanceof String s && !s.isBlank() ? s : null;
  }

  @ExceptionHandler(StripeNotConfiguredException.class)
  ResponseEntity<ProblemDetail> handleNotConfigured(StripeNotConfiguredException e) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    body.setTitle("Stripe not configured");
    body.setType(URI.create("https://arguslog.dev/problems/stripe-not-configured"));
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(CheckoutFailedException.class)
  ResponseEntity<ProblemDetail> handleFailed(CheckoutFailedException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    body.setTitle("Stripe checkout failed");
    body.setType(URI.create("https://arguslog.dev/problems/stripe-checkout-failed"));
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
