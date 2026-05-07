package org.arguslog.api.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.arguslog.api.security.ratelimit.ApiRateLimiter.Tier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitInterceptorTest {

  @AfterEach
  void clearAuth() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void firstRequestPassesAndSetsRemainingHeader() throws Exception {
    var limiter = new ApiRateLimiter(5, Duration.ofMinutes(1), 1, Duration.ofMinutes(1));
    var interceptor = new RateLimitInterceptor(limiter, Tier.DEFAULT);
    var req = new MockHttpServletRequest();
    req.setRemoteAddr("9.9.9.9");
    var res = new MockHttpServletResponse();

    boolean allowed = interceptor.preHandle(req, res, new Object());

    assertThat(allowed).isTrue();
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getHeader("X-RateLimit-Remaining")).isEqualTo("4");
  }

  @Test
  void exhaustedBucketReturns429WithRetryAfter() throws Exception {
    var limiter = new ApiRateLimiter(1, Duration.ofMinutes(1), 1, Duration.ofMinutes(1));
    var interceptor = new RateLimitInterceptor(limiter, Tier.DEFAULT);
    var req = new MockHttpServletRequest();
    req.setRemoteAddr("9.9.9.9");
    interceptor.preHandle(req, new MockHttpServletResponse(), new Object()); // consume

    var res = new MockHttpServletResponse();
    boolean allowed = interceptor.preHandle(req, res, new Object());

    assertThat(allowed).isFalse();
    assertThat(res.getStatus()).isEqualTo(429);
    assertThat(res.getHeader("Retry-After")).isNotNull();
    assertThat(Integer.parseInt(res.getHeader("Retry-After"))).isPositive();
    assertThat(res.getContentType()).contains("application/problem+json");
  }

  @Test
  void authenticatedRequestsKeyOnJwtSubNotIp() throws Exception {
    var limiter = new ApiRateLimiter(1, Duration.ofMinutes(1), 1, Duration.ofMinutes(1));
    var interceptor = new RateLimitInterceptor(limiter, Tier.DEFAULT);

    // Two requests from the SAME authenticated user but DIFFERENT IPs — both should hit the
    // same bucket because the key is the JWT sub, not the IP.
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("user-42", null, java.util.List.of()));

    var req1 = new MockHttpServletRequest();
    req1.setRemoteAddr("1.1.1.1");
    interceptor.preHandle(req1, new MockHttpServletResponse(), new Object());

    var req2 = new MockHttpServletRequest();
    req2.setRemoteAddr("2.2.2.2");
    var res2 = new MockHttpServletResponse();
    boolean secondAllowed = interceptor.preHandle(req2, res2, new Object());

    assertThat(secondAllowed).isFalse();
    assertThat(res2.getStatus()).isEqualTo(429);
  }

  @Test
  void xForwardedForPicksFirstHopWhenAnonymous() throws Exception {
    var limiter = new ApiRateLimiter(1, Duration.ofMinutes(1), 1, Duration.ofMinutes(1));
    var interceptor = new RateLimitInterceptor(limiter, Tier.DEFAULT);

    var req1 = new MockHttpServletRequest();
    req1.addHeader("X-Forwarded-For", "1.1.1.1, 10.0.0.1");
    interceptor.preHandle(req1, new MockHttpServletResponse(), new Object());

    // Same X-Forwarded-For first hop → same bucket → second request blocked.
    var req2 = new MockHttpServletRequest();
    req2.addHeader("X-Forwarded-For", "1.1.1.1, 10.0.0.5");
    var res2 = new MockHttpServletResponse();
    boolean secondAllowed = interceptor.preHandle(req2, res2, new Object());

    assertThat(secondAllowed).isFalse();
  }
}
