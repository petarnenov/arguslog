package org.arguslog.api.billing.application;

import com.stripe.model.Event;

public interface StripeWebhookUseCase {

  /**
   * Routes a verified Stripe event to the right handler. Returns the outcome so the controller can
   * shape the response — but Stripe only cares about a 2xx ACK either way.
   */
  Outcome handle(Event event);

  enum Outcome {
    /** First sight + handler ran cleanly. */
    PROCESSED,
    /** Already-processed event id; we no-op and ACK. */
    ALREADY_SEEN,
    /** Unknown / unhandled event type; we ACK without doing anything. */
    IGNORED,
    /** Event referenced an unknown customer; we ACK so Stripe stops retrying. */
    UNKNOWN_CUSTOMER
  }
}
