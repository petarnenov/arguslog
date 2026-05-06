package org.arguslog.api.billing.adapter.out.stripe;

import com.stripe.StripeClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds an instance-based {@link StripeClient}. We deliberately do NOT use the static {@code
 * Stripe.apiKey} approach — instance config is mockable in tests + lets us drop in a different api
 * key without restarting the process if we ever need that (P5).
 *
 * <p>Falls back to a sentinel "sk_unconfigured_for_dev" key when {@code arguslog.stripe.api-key} is
 * empty so Spring can still wire the bean. Endpoints that actually call Stripe should gate on
 * {@link StripeProperties#configured()} and return 503 themselves — the client object stays
 * harmless until something actually tries to make an HTTP call.
 */
@Configuration
@EnableConfigurationProperties(StripeProperties.class)
public class StripeConfig {

  private static final String DEV_PLACEHOLDER_KEY = "sk_unconfigured_for_dev";

  @Bean
  public StripeClient stripeClient(StripeProperties props) {
    String key = props.apiKey().isBlank() ? DEV_PLACEHOLDER_KEY : props.apiKey();
    return new StripeClient(key);
  }
}
