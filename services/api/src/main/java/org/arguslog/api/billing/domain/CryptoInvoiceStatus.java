package org.arguslog.api.billing.domain;

import java.util.Locale;

/**
 * Lifecycle of a NOWPayments hosted invoice. Mirrors the {@code crypto_invoice_status} Postgres
 * enum and the {@code payment_status} field in NOWPayments IPN payloads — except {@link #PENDING}
 * which is our own pre-IPN state (invoice row inserted, NOWPayments not yet contacted).
 *
 * <p>Terminal states: {@link #FINISHED}, {@link #FAILED}, {@link #REFUNDED}, {@link #EXPIRED}. Plan
 * upgrade fires only on {@link #FINISHED}.
 */
public enum CryptoInvoiceStatus {
  PENDING,
  WAITING,
  CONFIRMING,
  CONFIRMED,
  SENDING,
  PARTIALLY_PAID,
  FINISHED,
  FAILED,
  REFUNDED,
  EXPIRED;

  public String dbValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static CryptoInvoiceStatus fromDbValue(String raw) {
    if (raw == null) return PENDING;
    try {
      return CryptoInvoiceStatus.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return PENDING;
    }
  }
}
