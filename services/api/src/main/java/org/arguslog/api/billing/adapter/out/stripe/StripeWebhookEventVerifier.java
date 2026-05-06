package org.arguslog.api.billing.adapter.out.stripe;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.arguslog.api.billing.application.port.StripeEventVerifier;
import org.springframework.stereotype.Component;

/**
 * Production impl — delegates to {@link Webhook#constructEvent} which signs + parses in one shot.
 */
@Component
public class StripeWebhookEventVerifier implements StripeEventVerifier {

  @Override
  public Event verify(String payload, String signatureHeader, String secret)
      throws SignatureVerificationException {
    return Webhook.constructEvent(payload, signatureHeader, secret);
  }
}
