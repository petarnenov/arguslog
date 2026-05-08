package org.arguslog.api.billing.adapter.in.web;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import java.net.URI;
import org.arguslog.api.billing.adapter.out.stripe.StripeProperties;
import org.arguslog.api.billing.application.StripeWebhookUseCase;
import org.arguslog.api.billing.application.StripeWebhookUseCase.Outcome;
import org.arguslog.api.billing.application.port.StripeEventVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe webhook receiver. The route is {@code /api/v1/webhooks/stripe} which {@code
 * SecurityConfig} marks {@code permitAll} — Stripe POSTs anonymously and authenticates via the
 * {@code Stripe-Signature} header.
 *
 * <p>Verification goes through {@link StripeEventVerifier} (wraps Stripe's static {@code
 * Webhook.constructEvent}) which validates the HMAC-SHA256 signature against the configured webhook
 * secret. Anything that fails verification gets a 400 — Stripe will keep retrying until we accept
 * (or until they give up after their max-redelivery window).
 *
 * <p>Body is bound as raw {@code String} (not Jackson-parsed) because the signature is computed
 * over the exact bytes Stripe sent. A reformatted-then-reserialized copy would no longer match.
 *
 * <p>Error responses follow RFC 9457 ProblemDetail. Stripe itself ignores the body (it only reads
 * the status code) but consistent shape across the api keeps ops dashboards honest.
 */
@RestController
@RequestMapping(value = "/api/v1/webhooks", produces = MediaType.APPLICATION_JSON_VALUE)
public class StripeWebhookController {

  private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

  private final StripeWebhookUseCase useCase;
  private final StripeEventVerifier verifier;
  private final StripeProperties props;

  public StripeWebhookController(
      StripeWebhookUseCase useCase, StripeEventVerifier verifier, StripeProperties props) {
    this.useCase = useCase;
    this.verifier = verifier;
    this.props = props;
  }

  @PostMapping(value = "/stripe", consumes = MediaType.APPLICATION_JSON_VALUE)
  public StripeWebhookResponse receive(
      @RequestBody String payload,
      @RequestHeader(value = "Stripe-Signature", required = false) String signature) {

    if (props.webhookSecret().isBlank()) {
      log.warn("received Stripe webhook but arguslog.stripe.webhook-secret is unset — rejecting");
      throw new WebhookUnavailableException(
          "Stripe webhook receiver is not configured on this deployment.");
    }
    if (signature == null || signature.isBlank()) {
      throw new MissingStripeSignatureException("Stripe-Signature header is required.");
    }

    Event event;
    try {
      event = verifier.verify(payload, signature, props.webhookSecret());
    } catch (SignatureVerificationException e) {
      log.warn("Stripe webhook signature mismatch: {}", e.getMessage());
      throw new InvalidStripeSignatureException(
          "Stripe-Signature header did not validate against the configured webhook secret.");
    } catch (RuntimeException e) {
      // Body parse error — Stripe sent malformed JSON, or constructEvent choked. Reject so it
      // can re-deliver if it was transient.
      log.warn("Stripe webhook body could not be parsed: {}", e.getMessage());
      throw new InvalidStripePayloadException(
          "Webhook body could not be parsed as a Stripe event.");
    }

    try {
      Outcome outcome = useCase.handle(event);
      // Always return 200 on accepted events — even ALREADY_SEEN / IGNORED / UNKNOWN_CUSTOMER —
      // so Stripe stops redelivering. The body is informational only.
      return new StripeWebhookResponse(outcome.name().toLowerCase());
    } catch (RuntimeException e) {
      log.error(
          "stripe webhook handler crashed for event {}: {}", event.getId(), e.getMessage(), e);
      // 5xx so Stripe redelivers; the eventLog row was rolled back with the @Transactional
      // boundary so the handler can run cleanly on retry.
      throw new StripeHandlerFailedException("Webhook handler failed; Stripe will redeliver.");
    }
  }

  // ── exception → ProblemDetail mapping ────────────────────────────────────

  @ExceptionHandler(WebhookUnavailableException.class)
  ResponseEntity<ProblemDetail> handleUnavailable(WebhookUnavailableException e) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Webhook unavailable",
        "stripe-webhook-secret-unset",
        e.getMessage());
  }

  @ExceptionHandler(MissingStripeSignatureException.class)
  ResponseEntity<ProblemDetail> handleMissing(MissingStripeSignatureException e) {
    return problem(
        HttpStatus.BAD_REQUEST, "Missing signature", "stripe-missing-signature", e.getMessage());
  }

  @ExceptionHandler(InvalidStripeSignatureException.class)
  ResponseEntity<ProblemDetail> handleInvalidSig(InvalidStripeSignatureException e) {
    return problem(
        HttpStatus.BAD_REQUEST, "Invalid signature", "stripe-invalid-signature", e.getMessage());
  }

  @ExceptionHandler(InvalidStripePayloadException.class)
  ResponseEntity<ProblemDetail> handleInvalidPayload(InvalidStripePayloadException e) {
    return problem(
        HttpStatus.BAD_REQUEST, "Invalid payload", "stripe-invalid-payload", e.getMessage());
  }

  @ExceptionHandler(StripeHandlerFailedException.class)
  ResponseEntity<ProblemDetail> handleHandlerFailed(StripeHandlerFailedException e) {
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Webhook handler failed",
        "stripe-handler-failed",
        e.getMessage());
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String title, String typeSlug, String detail) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setTitle(title);
    body.setType(URI.create("https://arguslog.org/problems/" + typeSlug));
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  // ── exception types ──────────────────────────────────────────────────────

  static final class WebhookUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    WebhookUnavailableException(String message) {
      super(message);
    }
  }

  static final class MissingStripeSignatureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    MissingStripeSignatureException(String message) {
      super(message);
    }
  }

  static final class InvalidStripeSignatureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidStripeSignatureException(String message) {
      super(message);
    }
  }

  static final class InvalidStripePayloadException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidStripePayloadException(String message) {
      super(message);
    }
  }

  static final class StripeHandlerFailedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    StripeHandlerFailedException(String message) {
      super(message);
    }
  }
}
