package org.arguslog.api.billing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.billing.adapter.out.nowpayments.NowPaymentsClient;
import org.arguslog.api.billing.adapter.out.nowpayments.NowPaymentsClient.CreateInvoiceRequest;
import org.arguslog.api.billing.adapter.out.nowpayments.NowPaymentsClient.CreateInvoiceResponse;
import org.arguslog.api.billing.adapter.out.nowpayments.NowPaymentsProperties;
import org.arguslog.api.billing.application.port.CryptoInvoiceRepository;
import org.arguslog.api.billing.domain.CryptoInvoice;
import org.arguslog.api.billing.domain.PlanTier;
import org.arguslog.api.domain.Org;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CryptoCheckoutService implements CryptoCheckoutUseCase {

  private static final Logger log = LoggerFactory.getLogger(CryptoCheckoutService.class);

  private final NowPaymentsClient client;
  private final NowPaymentsProperties props;
  private final CryptoInvoiceRepository invoices;
  private final OrgWriteRepository orgs;

  public CryptoCheckoutService(
      NowPaymentsClient client,
      NowPaymentsProperties props,
      CryptoInvoiceRepository invoices,
      OrgWriteRepository orgs) {
    this.client = client;
    this.props = props;
    this.invoices = invoices;
    this.orgs = orgs;
  }

  @Override
  @Transactional
  public CheckoutResult start(long orgId, int durationMonths) {
    if (!props.configured()) {
      throw new CryptoCheckoutNotConfiguredException(
          "NOWPayments is not configured on this deployment — set arguslog.nowpayments.api-key"
              + " and arguslog.nowpayments.ipn-secret to enable crypto checkout.");
    }

    int amountCents = PlanTier.PRO.priceCentsForDuration(durationMonths);
    String orgSlug =
        orgs.findById(orgId)
            .map(Org::slug)
            .orElseThrow(
                () ->
                    new CryptoCheckoutFailedException(
                        "Org " + orgId + " disappeared between access-guard and checkout", null));

    CryptoInvoice pending = invoices.insertPending(orgId, durationMonths, amountCents);

    BigDecimal priceUsd = BigDecimal.valueOf(amountCents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    CreateInvoiceRequest request =
        new CreateInvoiceRequest(
            priceUsd,
            "USD",
            pending.internalReference().toString(),
            "Arguslog Pro — " + durationMonths + " month" + (durationMonths == 1 ? "" : "s"),
            props.ipnCallbackUrl(),
            props.successUrl(orgSlug),
            props.cancelUrl(orgSlug),
            false);

    CreateInvoiceResponse response;
    try {
      response = client.createInvoice(request);
    } catch (RuntimeException e) {
      log.warn(
          "NOWPayments createInvoice failed for org {} ref {}: {}",
          orgId,
          pending.internalReference(),
          e.getMessage());
      throw new CryptoCheckoutFailedException(
          "NOWPayments rejected the invoice creation: " + e.getMessage(), e);
    }

    invoices.attachNpInvoice(pending.internalReference(), response.id(), response.invoiceUrl());

    log.info(
        "crypto checkout minted: org {} months={} amount={}c npInvoice={} ref={}",
        orgId,
        durationMonths,
        amountCents,
        response.id(),
        pending.internalReference());

    return new CheckoutResult(response.invoiceUrl(), pending.internalReference().toString());
  }
}
