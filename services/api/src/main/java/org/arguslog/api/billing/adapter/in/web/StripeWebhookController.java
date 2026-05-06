package org.arguslog.api.billing.adapter.in.web;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import org.arguslog.api.billing.adapter.out.stripe.StripeProperties;
import org.arguslog.api.billing.application.StripeWebhookUseCase;
import org.arguslog.api.billing.application.StripeWebhookUseCase.Outcome;
import org.arguslog.api.billing.application.port.StripeEventVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
  public ResponseEntity<String> receive(
      @RequestBody String payload,
      @RequestHeader(value = "Stripe-Signature", required = false) String signature) {

    if (props.webhookSecret().isBlank()) {
      log.warn("received Stripe webhook but arguslog.stripe.webhook-secret is unset — rejecting");
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body("{\"error\":\"webhook_secret_unset\"}");
    }
    if (signature == null || signature.isBlank()) {
      return ResponseEntity.badRequest().body("{\"error\":\"missing_signature\"}");
    }

    Event event;
    try {
      event = verifier.verify(payload, signature, props.webhookSecret());
    } catch (SignatureVerificationException e) {
      log.warn("Stripe webhook signature mismatch: {}", e.getMessage());
      return ResponseEntity.badRequest().body("{\"error\":\"invalid_signature\"}");
    } catch (RuntimeException e) {
      // Body parse error — Stripe sent malformed JSON, or constructEvent choked. Reject so it
      // can re-deliver if it was transient.
      log.warn("Stripe webhook body could not be parsed: {}", e.getMessage());
      return ResponseEntity.badRequest().body("{\"error\":\"invalid_payload\"}");
    }

    try {
      Outcome outcome = useCase.handle(event);
      // Always return 200 on accepted events — even ALREADY_SEEN / IGNORED / UNKNOWN_CUSTOMER —
      // so Stripe stops redelivering. The body is informational only.
      return ResponseEntity.ok("{\"outcome\":\"" + outcome.name().toLowerCase() + "\"}");
    } catch (RuntimeException e) {
      log.error(
          "stripe webhook handler crashed for event {}: {}", event.getId(), e.getMessage(), e);
      // 5xx so Stripe redelivers; the eventLog row was rolled back with the @Transactional
      // boundary so the handler can run cleanly on retry.
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("{\"error\":\"handler_failed\"}");
    }
  }
}
