package org.arguslog.api.billing.adapter.in.web;

import java.net.URI;
import java.util.Locale;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.billing.adapter.in.web.dto.CheckoutResponse;
import org.arguslog.api.billing.adapter.in.web.dto.CryptoCheckoutResponse;
import org.arguslog.api.billing.application.CheckoutUseCase;
import org.arguslog.api.billing.application.CryptoCheckoutUseCase;
import org.arguslog.api.billing.application.CryptoCheckoutUseCase.CheckoutResult;
import org.arguslog.api.billing.application.PortalUseCase;
import org.arguslog.api.billing.application.PortalUseCase.NoCustomerException;
import org.arguslog.api.billing.domain.BillingInterval;
import org.arguslog.api.security.AuthActor;
import org.arguslog.billing.PlanTier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-level billing endpoints (V26+). Sits under {@code /api/v1/me/billing} so the dashboard
 * doesn't have to know which of the user's orgs to point Stripe / NOWPayments at.
 *
 * <p>Under the hood each call resolves the user's "primary owned org" (highest tier, earliest
 * membership tiebreak) and delegates to the existing org-scoped checkout / portal services. After
 * per-user dual-write (Phase 1+2) all webhook mutations land on both org and user rows, so the
 * indirection through one org is invisible to the caller.
 *
 * <p>Users without an owned org get a 409 — Stripe needs SOMETHING to bill against, and the "no
 * orgs yet" state means the user hasn't even completed onboarding. The frontend should send them
 * through {@code /onboarding} before exposing the billing form.
 */
@RestController
@RequestMapping(value = "/api/v1/me/billing", produces = MediaType.APPLICATION_JSON_VALUE)
public class MeBillingController {

  private static final java.util.Set<Integer> ALLOWED_DURATIONS = java.util.Set.of(1, 3, 6, 12);

  private final CheckoutUseCase checkout;
  private final PortalUseCase portal;
  private final CryptoCheckoutUseCase crypto;
  private final MembershipRepository memberships;

  public MeBillingController(
      CheckoutUseCase checkout,
      PortalUseCase portal,
      CryptoCheckoutUseCase crypto,
      MembershipRepository memberships) {
    this.checkout = checkout;
    this.portal = portal;
    this.crypto = crypto;
    this.memberships = memberships;
  }

  @PostMapping("/checkout-session")
  public CheckoutResponse start(
      @RequestParam(name = "interval", required = false, defaultValue = "monthly")
          String interval) {
    long orgId = requirePrimaryOwnedOrg();
    BillingInterval billingInterval = parseInterval(interval);
    return new CheckoutResponse(
        checkout.createCheckoutUrl(orgId, currentUserEmail(), billingInterval));
  }

  @PostMapping("/portal")
  public CheckoutResponse portal() {
    long orgId = requirePrimaryOwnedOrg();
    return new CheckoutResponse(portal.createPortalUrl(orgId));
  }

  @PostMapping("/crypto-invoice")
  public CryptoCheckoutResponse cryptoInvoice(
      @RequestParam(name = "tier", required = false, defaultValue = "pro") String tierRaw,
      @RequestParam(name = "duration", required = false, defaultValue = "1") int durationMonths) {
    long orgId = requirePrimaryOwnedOrg();
    if (!ALLOWED_DURATIONS.contains(durationMonths)) {
      throw new InvalidIntervalException(
          "Unsupported duration: " + durationMonths + " months. Allowed: 1, 3, 6, 12.");
    }
    PlanTier tier = parseTier(tierRaw);
    if (!tier.isPaid()) {
      throw new InvalidIntervalException(
          "Tier "
              + tier.dbValue()
              + " is not sold via self-serve. Allowed: starter, pro, business.");
    }
    CheckoutResult result = crypto.start(orgId, tier, durationMonths);
    return new CryptoCheckoutResponse(result.checkoutUrl(), result.invoiceReference());
  }

  private long requirePrimaryOwnedOrg() {
    return memberships
        .findPrimaryOwnedOrg(AuthActor.currentUserId())
        .orElseThrow(NoOwnedOrgException::new);
  }

  private static BillingInterval parseInterval(String raw) {
    if (raw == null) return BillingInterval.MONTHLY;
    try {
      return BillingInterval.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidIntervalException("Unknown interval: " + raw);
    }
  }

  private static PlanTier parseTier(String raw) {
    if (raw == null) throw new InvalidIntervalException("tier is required");
    try {
      return PlanTier.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidIntervalException("Unknown tier: " + raw);
    }
  }

  private static String currentUserEmail() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwtAuth)) return null;
    Jwt jwt = jwtAuth.getToken();
    Object email = jwt.getClaim("email");
    return email instanceof String s && !s.isBlank() ? s : null;
  }

  static final class InvalidIntervalException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidIntervalException(String message) {
      super(message);
    }
  }

  static final class NoOwnedOrgException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NoOwnedOrgException() {
      super("You don't own any organization yet — create one before configuring billing.");
    }
  }

  @ExceptionHandler(NoOwnedOrgException.class)
  ResponseEntity<ProblemDetail> handleNoOwnedOrg(NoOwnedOrgException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    body.setTitle("No owned organization");
    body.setType(URI.create("https://arguslog.org/problems/no-owned-org"));
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(InvalidIntervalException.class)
  ResponseEntity<ProblemDetail> handleInvalid(InvalidIntervalException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid billing parameter");
    body.setType(URI.create("https://arguslog.org/problems/invalid-billing-parameter"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(NoCustomerException.class)
  ResponseEntity<ProblemDetail> handleNoCustomer(NoCustomerException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    body.setTitle("No Stripe customer yet");
    body.setType(URI.create("https://arguslog.org/problems/no-stripe-customer"));
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
