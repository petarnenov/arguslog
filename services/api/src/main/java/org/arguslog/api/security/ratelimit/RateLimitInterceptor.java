package org.arguslog.api.security.ratelimit;

import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.arguslog.api.security.ratelimit.ApiRateLimiter.Tier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Spring MVC interceptor that consults {@link ApiRateLimiter} for every request. Key derivation:
 *
 * <ul>
 *   <li>Authenticated request → bucket key is {@code "u:<jwt-sub>"}. Stops one user with
 *       compromised credentials from drowning the API.
 *   <li>Anonymous request → bucket key is {@code "ip:<client-ip>"}. {@code X-Forwarded-For} is
 *       trusted because Cloudflare is the first hop on app/api/auth subdomains; ingest is not
 *       proxied so its requests don't pass through this interceptor anyway.
 * </ul>
 *
 * <p>Tier selection lives in {@link RateLimitWebMvcConfig} via per-path registration — the
 * interceptor itself is tier-agnostic and reads the bound tier from the request attribute that the
 * registration sets.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

  /** Request attribute name set by the registration to indicate which tier to apply. */
  static final String TIER_ATTRIBUTE = ApiRateLimiter.class.getName() + ".tier";

  private final ApiRateLimiter limiter;
  private final Tier tier;

  public RateLimitInterceptor(ApiRateLimiter limiter, Tier tier) {
    this.limiter = limiter;
    this.tier = tier;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    String key = key(request);
    ConsumptionProbe probe = limiter.tryConsume(tier, key);
    response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
    if (probe.isConsumed()) {
      return true;
    }
    long retryAfterSeconds =
        Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).getSeconds());
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response
        .getWriter()
        .write(
            "{\"title\":\"Rate limit exceeded\",\"status\":429,\"retryAfterSeconds\":"
                + retryAfterSeconds
                + "}");
    return false;
  }

  private static String key(HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
      return "u:" + auth.getName();
    }
    return "ip:" + clientIp(request);
  }

  private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int comma = forwarded.indexOf(',');
      return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
    }
    return request.getRemoteAddr();
  }
}
