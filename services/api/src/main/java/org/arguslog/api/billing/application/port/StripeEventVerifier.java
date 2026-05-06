package org.arguslog.api.billing.application.port;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;

/**
 * Wraps Stripe's static {@code Webhook.constructEvent} so the controller can be unit-tested without
 * reaching for static-method mocks. Real impl in {@code adapter/out/stripe}; tests stub the whole
 * port.
 */
public interface StripeEventVerifier {

  Event verify(String payload, String signatureHeader, String secret)
      throws SignatureVerificationException;
}
