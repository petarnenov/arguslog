package org.arguslog.api.security.ratelimit;

import org.arguslog.api.security.ratelimit.ApiRateLimiter.Tier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the per-path tiers. STRICT applies to public, non-authed write endpoints (Stripe webhook,
 * the only one today). DEFAULT covers everything else under {@code /api/**}; {@code /actuator/**}
 * stays unrate-limited so health probes never trip on a noisy neighbour.
 *
 * <p>Disable with {@code arguslog.ratelimit.enabled=false} (defaults to true). Useful in controller
 * tests that don't care about back-pressure.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arguslog.ratelimit",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RateLimitWebMvcConfig implements WebMvcConfigurer {

  private final ApiRateLimiter limiter;

  public RateLimitWebMvcConfig(ApiRateLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // Strict tier first — wins for matching paths because Spring picks the most-specific
    // registered interceptor when multiple include a path.
    registry
        .addInterceptor(new RateLimitInterceptor(limiter, Tier.STRICT))
        .addPathPatterns("/api/v1/webhooks/**");

    registry
        .addInterceptor(new RateLimitInterceptor(limiter, Tier.DEFAULT))
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/v1/webhooks/**");
  }
}
