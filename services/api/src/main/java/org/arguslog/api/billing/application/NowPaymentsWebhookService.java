package org.arguslog.api.billing.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.billing.application.port.CryptoEventLog;
import org.arguslog.api.billing.application.port.CryptoInvoiceRepository;
import org.arguslog.api.billing.domain.BillingProvider;
import org.arguslog.api.billing.domain.CryptoInvoice;
import org.arguslog.api.billing.domain.CryptoInvoiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Routes verified NOWPayments IPN payloads to plan-state mutations.
 *
 * <p>NOWPayments sends an IPN at every status transition: {@code waiting → confirming → confirmed →
 * sending → finished} (or one of the failure terminals). We update {@code crypto_invoices.status}
 * on every IPN, but only {@code finished} fires the plan upgrade through {@link
 * ApplyPlanPurchaseUseCase}. {@code partially_paid} is logged but not upgraded — the user sent less
 * than billed; the dashboard will show "incomplete" and they can either top up or abandon.
 *
 * <p>Idempotency: dedup on the {@code (payment_id, payment_status)} tuple in {@code crypto_events}
 * via {@link CryptoEventLog#recordIfNew}. The plan upgrade itself is also idempotent
 * (cross-provider {@code plan_purchases} unique constraint), so even a logic bug here won't
 * double-extend the plan.
 *
 * <p>Invoice correlation: NOWPayments echoes our {@code order_id} (the invoice's internal UUID
 * reference) on every IPN; that's our preferred lookup. {@code np_invoice_id} is also stored on the
 * row by the checkout creation path, but only {@code order_id} is guaranteed present on
 * partial-payment IPNs in our experience.
 */
@Service
public class NowPaymentsWebhookService implements NowPaymentsWebhookUseCase {

  private static final Logger log = LoggerFactory.getLogger(NowPaymentsWebhookService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final CryptoEventLog eventLog;
  private final CryptoInvoiceRepository invoices;
  private final ApplyPlanPurchaseUseCase applyPurchase;

  public NowPaymentsWebhookService(
      CryptoEventLog eventLog,
      CryptoInvoiceRepository invoices,
      ApplyPlanPurchaseUseCase applyPurchase) {
    this.eventLog = eventLog;
    this.invoices = invoices;
    this.applyPurchase = applyPurchase;
  }

  @Override
  @Transactional
  public Outcome handle(String rawJsonBody) {
    JsonNode body;
    try {
      body = MAPPER.readTree(rawJsonBody);
    } catch (Exception e) {
      log.warn("nowpayments IPN body parse failure: {}", e.getMessage());
      return Outcome.IGNORED;
    }

    String paymentId = textOrNull(body, "payment_id");
    String paymentStatus = textOrNull(body, "payment_status");
    String orderId = textOrNull(body, "order_id");
    if (paymentStatus == null) {
      log.warn("nowpayments IPN missing payment_status; ignoring");
      return Outcome.IGNORED;
    }
    if (paymentId == null) {
      log.warn("nowpayments IPN missing payment_id; ignoring");
      return Outcome.IGNORED;
    }

    if (!eventLog.recordIfNew(paymentId, paymentStatus)) {
      log.debug("nowpayments IPN already seen: payment_id={} status={}", paymentId, paymentStatus);
      return Outcome.ALREADY_SEEN;
    }

    Optional<CryptoInvoice> located = locateInvoice(orderId, paymentId);
    if (located.isEmpty()) {
      log.warn(
          "nowpayments IPN for unknown invoice: payment_id={} order_id={} status={}",
          paymentId,
          orderId,
          paymentStatus);
      return Outcome.UNKNOWN_INVOICE;
    }

    CryptoInvoice invoice = located.get();
    CryptoInvoiceStatus status = CryptoInvoiceStatus.fromDbValue(paymentStatus);
    invoices.applyIpnUpdate(
        invoice.id(),
        paymentId,
        status,
        bigDecimalOrEmpty(body, "actually_paid", "pay_amount"),
        Optional.ofNullable(textOrNull(body, "pay_currency")),
        parseInstant(body, "expiration_estimate_date", "valid_until"),
        rawJsonBody);

    if (status != CryptoInvoiceStatus.FINISHED) {
      log.info(
          "nowpayments IPN: invoice {} -> {} (no plan change)", invoice.id(), status.dbValue());
      return Outcome.PROCESSED;
    }

    String reference = invoice.internalReference().toString();
    String payCurrency = textOrNull(body, "pay_currency");

    applyPurchase.apply(
        invoice.orgId(),
        BillingProvider.NOWPAYMENTS,
        reference,
        invoice.plan(),
        invoice.durationMonths(),
        invoice.priceAmountCents(),
        invoice.priceCurrency(),
        Optional.ofNullable(payCurrency));

    log.info(
        "nowpayments IPN: invoice {} FINISHED — plan upgraded for org {}",
        invoice.id(),
        invoice.orgId());
    return Outcome.PROCESSED;
  }

  private Optional<CryptoInvoice> locateInvoice(String orderId, String paymentId) {
    if (orderId != null) {
      try {
        UUID ref = UUID.fromString(orderId);
        Optional<CryptoInvoice> byRef = invoices.findByInternalReference(ref);
        if (byRef.isPresent()) return byRef;
      } catch (IllegalArgumentException ignored) {
        // order_id wasn't a UUID — fall through to other lookups
      }
    }
    if (paymentId != null) {
      Optional<CryptoInvoice> byPayment = invoices.findByNpPaymentId(paymentId);
      if (byPayment.isPresent()) return byPayment;
    }
    return Optional.empty();
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) return null;
    String text = value.asText();
    return text.isBlank() ? null : text;
  }

  private static Optional<BigDecimal> bigDecimalOrEmpty(JsonNode node, String... fields) {
    for (String field : fields) {
      JsonNode value = node.get(field);
      if (value == null || value.isNull()) continue;
      try {
        return Optional.of(new BigDecimal(value.asText()));
      } catch (NumberFormatException ignored) {
        // try the next field
      }
    }
    return Optional.empty();
  }

  private static Optional<Instant> parseInstant(JsonNode node, String... fields) {
    for (String field : fields) {
      String text = textOrNull(node, field);
      if (text == null) continue;
      try {
        return Optional.of(Instant.parse(text));
      } catch (DateTimeParseException ignored) {
        // NOWPayments sometimes returns ISO-without-Z; safe to skip and keep the existing value
      }
    }
    return Optional.empty();
  }
}
