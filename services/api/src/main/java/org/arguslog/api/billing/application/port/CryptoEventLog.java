package org.arguslog.api.billing.application.port;

/**
 * Idempotency log for NOWPayments IPN deliveries. NOWPayments redelivers on 5xx and on the
 * occasional duplicate "finished" notification; we dedupe on the {@code (payment_id,
 * payment_status)} tuple so the lifecycle ({@code waiting → confirming → finished}) gets
 * exactly-once processing without rejecting a legitimate later status update.
 */
public interface CryptoEventLog {

  /** {@code true} if this {@code (paymentId, status)} pair was newly recorded, {@code false} if seen. */
  boolean recordIfNew(String paymentId, String status);
}
