package org.arguslog.api.billing.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.billing.domain.CryptoInvoice;
import org.arguslog.api.billing.domain.CryptoInvoiceStatus;
import org.arguslog.api.billing.domain.PlanTier;

/**
 * State for one NOWPayments hosted checkout we minted. The invoice row is created BEFORE we hit
 * NOWPayments — its {@code internal_reference} (UUID) is what we send as the provider's
 * {@code order_id}, so when the IPN arrives we can resolve back to the org regardless of whether
 * NOWPayments returned the invoice id to us in time.
 */
public interface CryptoInvoiceRepository {

  CryptoInvoice insertPending(long orgId, PlanTier plan, int durationMonths, int priceAmountCents);

  Optional<CryptoInvoice> findByInternalReference(UUID internalReference);

  Optional<CryptoInvoice> findByNpInvoiceId(String npInvoiceId);

  Optional<CryptoInvoice> findByNpPaymentId(String npPaymentId);

  void attachNpInvoice(UUID internalReference, String npInvoiceId, String checkoutUrl);

  /** Updates status + optionally the payment id and last payload after an IPN delivery. */
  void applyIpnUpdate(
      long invoiceId,
      String npPaymentId,
      CryptoInvoiceStatus status,
      Optional<BigDecimal> payAmount,
      Optional<String> payCurrency,
      Optional<Instant> expiresAt,
      String rawPayloadJson);
}
