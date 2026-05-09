package org.arguslog.api.billing.adapter.out.nowpayments;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Thin wrapper over the NOWPayments REST surface we actually use:
 *
 * <ul>
 *   <li>{@code POST /v1/invoice} — mints a hosted checkout invoice
 *   <li>{@code GET /v1/payment/{payment_id}} — read-back used by reconciliation jobs
 * </ul>
 *
 * <p>Connection + read timeouts are short (~10s). NOWPayments is occasionally slow during peak
 * but we'd rather fail fast and let the user retry the checkout button than block a request
 * thread for a minute.
 */
@Component
public class NowPaymentsClient {

  private static final Logger log = LoggerFactory.getLogger(NowPaymentsClient.class);

  private final NowPaymentsProperties props;
  private final RestClient http;

  public NowPaymentsClient(NowPaymentsProperties props) {
    this.props = props;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
    factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
    this.http =
        RestClient.builder()
            .baseUrl(props.apiBaseUrl())
            .requestFactory(factory)
            .defaultHeader("x-api-key", props.apiKey())
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build();
  }

  /** Mints a hosted checkout invoice. The {@code orderId} is our internal correlation token. */
  public CreateInvoiceResponse createInvoice(CreateInvoiceRequest request) {
    try {
      CreateInvoiceResponse response =
          http.post()
              .uri("/invoice")
              .body(request)
              .retrieve()
              .onStatus(HttpStatusCode::isError, (req, res) -> {
                String body = new String(res.getBody().readAllBytes());
                throw new NowPaymentsException(
                    "NOWPayments createInvoice failed: " + res.getStatusCode() + " " + body);
              })
              .body(CreateInvoiceResponse.class);
      if (response == null || response.invoiceUrl() == null) {
        throw new NowPaymentsException("NOWPayments returned an empty invoice response");
      }
      return response;
    } catch (RestClientException e) {
      log.warn("NOWPayments createInvoice transport failure: {}", e.getMessage());
      throw new NowPaymentsException("NOWPayments createInvoice transport failure", e);
    }
  }

  public record CreateInvoiceRequest(
      @JsonProperty("price_amount") BigDecimal priceAmount,
      @JsonProperty("price_currency") String priceCurrency,
      @JsonProperty("order_id") String orderId,
      @JsonProperty("order_description") String orderDescription,
      @JsonProperty("ipn_callback_url") String ipnCallbackUrl,
      @JsonProperty("success_url") String successUrl,
      @JsonProperty("cancel_url") String cancelUrl,
      @JsonProperty("is_fee_paid_by_user") Boolean feePaidByUser) {}

  public record CreateInvoiceResponse(
      @JsonProperty("id") String id,
      @JsonProperty("invoice_url") String invoiceUrl,
      @JsonProperty("created_at") String createdAt,
      @JsonProperty("updated_at") String updatedAt) {}

  public static class NowPaymentsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NowPaymentsException(String message) {
      super(message);
    }

    public NowPaymentsException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
