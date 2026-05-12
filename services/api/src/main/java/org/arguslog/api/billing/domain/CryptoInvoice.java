package org.arguslog.api.billing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.billing.PlanTier;

public record CryptoInvoice(
    long id,
    long orgId,
    UUID internalReference,
    Optional<String> npInvoiceId,
    Optional<String> npPaymentId,
    PlanTier plan,
    int durationMonths,
    int priceAmountCents,
    String priceCurrency,
    Optional<BigDecimal> payAmount,
    Optional<String> payCurrency,
    CryptoInvoiceStatus status,
    Optional<String> checkoutUrl,
    Optional<Instant> expiresAt,
    Instant createdAt,
    Instant updatedAt) {}
