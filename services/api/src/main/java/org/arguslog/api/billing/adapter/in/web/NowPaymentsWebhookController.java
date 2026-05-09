package org.arguslog.api.billing.adapter.in.web;

import java.net.URI;
import org.arguslog.api.billing.adapter.out.nowpayments.NowPaymentsIpnVerifier;
import org.arguslog.api.billing.adapter.out.nowpayments.NowPaymentsProperties;
import org.arguslog.api.billing.application.NowPaymentsWebhookUseCase;
import org.arguslog.api.billing.application.NowPaymentsWebhookUseCase.Outcome;
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
 * NOWPayments IPN receiver. Mounted at {@code /api/v1/webhooks/nowpayments}; {@code
 * SecurityConfig} marks it {@code permitAll} — NOWPayments POSTs anonymously and authenticates via
 * {@code x-nowpayments-sig} (HMAC-SHA512 over the alphabetically-sorted JSON body).
 *
 * <p>Body is bound as raw {@code String} because the signature is computed over the canonical JSON
 * we re-serialize from those bytes; if Spring re-parsed/re-serialized the request body for us the
 * key ordering would no longer be deterministic.
 */
@RestController
@RequestMapping(value = "/api/v1/webhooks", produces = MediaType.APPLICATION_JSON_VALUE)
public class NowPaymentsWebhookController {

  private static final Logger log = LoggerFactory.getLogger(NowPaymentsWebhookController.class);

  private final NowPaymentsWebhookUseCase useCase;
  private final NowPaymentsIpnVerifier verifier;
  private final NowPaymentsProperties props;

  public NowPaymentsWebhookController(
      NowPaymentsWebhookUseCase useCase,
      NowPaymentsIpnVerifier verifier,
      NowPaymentsProperties props) {
    this.useCase = useCase;
    this.verifier = verifier;
    this.props = props;
  }

  @PostMapping(value = "/nowpayments", consumes = MediaType.APPLICATION_JSON_VALUE)
  public NowPaymentsWebhookResponse receive(
      @RequestBody String payload,
      @RequestHeader(value = "x-nowpayments-sig", required = false) String signature) {

    if (!props.configured()) {
      log.warn("nowpayments IPN received but provider is not configured — rejecting");
      throw new WebhookUnavailableException(
          "NOWPayments webhook receiver is not configured on this deployment.");
    }
    if (signature == null || signature.isBlank()) {
      throw new MissingSignatureException("x-nowpayments-sig header is required.");
    }
    if (!verifier.isValid(payload, signature)) {
      log.warn("nowpayments IPN signature mismatch");
      throw new InvalidSignatureException(
          "x-nowpayments-sig header did not validate against the configured IPN secret.");
    }

    try {
      Outcome outcome = useCase.handle(payload);
      return new NowPaymentsWebhookResponse(outcome.name().toLowerCase());
    } catch (RuntimeException e) {
      log.error("nowpayments IPN handler crashed: {}", e.getMessage(), e);
      throw new HandlerFailedException("Webhook handler failed; NOWPayments will redeliver.");
    }
  }

  // ── exception → ProblemDetail mapping ────────────────────────────────────

  @ExceptionHandler(WebhookUnavailableException.class)
  ResponseEntity<ProblemDetail> handleUnavailable(WebhookUnavailableException e) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Webhook unavailable",
        "nowpayments-not-configured",
        e.getMessage());
  }

  @ExceptionHandler(MissingSignatureException.class)
  ResponseEntity<ProblemDetail> handleMissing(MissingSignatureException e) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Missing signature",
        "nowpayments-missing-signature",
        e.getMessage());
  }

  @ExceptionHandler(InvalidSignatureException.class)
  ResponseEntity<ProblemDetail> handleInvalidSig(InvalidSignatureException e) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Invalid signature",
        "nowpayments-invalid-signature",
        e.getMessage());
  }

  @ExceptionHandler(HandlerFailedException.class)
  ResponseEntity<ProblemDetail> handleHandlerFailed(HandlerFailedException e) {
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Webhook handler failed",
        "nowpayments-handler-failed",
        e.getMessage());
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String title, String typeSlug, String detail) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setTitle(title);
    body.setType(URI.create("https://arguslog.org/problems/" + typeSlug));
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  static final class WebhookUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    WebhookUnavailableException(String message) {
      super(message);
    }
  }

  static final class MissingSignatureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    MissingSignatureException(String message) {
      super(message);
    }
  }

  static final class InvalidSignatureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidSignatureException(String message) {
      super(message);
    }
  }

  static final class HandlerFailedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    HandlerFailedException(String message) {
      super(message);
    }
  }
}
