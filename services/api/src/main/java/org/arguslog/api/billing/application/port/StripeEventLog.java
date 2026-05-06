package org.arguslog.api.billing.application.port;

/**
 * Atomic "have we seen this Stripe event yet" check. Single round-trip — INSERT … ON CONFLICT DO
 * NOTHING returns true on first sight, false if the row was already there.
 *
 * <p>Stripe redelivers any event whose receiver returned non-2xx; we use this as the dedup gate at
 * the top of the webhook handler.
 */
public interface StripeEventLog {

  /**
   * @return true on first sight (caller proceeds with the handler), false if seen previously
   *     (caller short-circuits with 200 OK so Stripe stops redelivering).
   */
  boolean recordIfNew(String eventId, String eventType);
}
