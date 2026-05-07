package org.arguslog.api.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory token bucket per request key. Two configured tiers:
 *
 * <ul>
 *   <li>{@code default} — applied to every dashboard / actuator request. Generous enough that a
 *       legitimate user clicking through pages never trips it.
 *   <li>{@code strict} — applied to public, non-authed endpoints (Stripe webhook, eventually any
 *       sign-up surface) where the cost of a flood is higher and the typical customer cadence is
 *       lower.
 * </ul>
 *
 * <p>Per-instance only. Across N replicas the effective ceiling is {@code N × tokens}; that's fine
 * for the design — the bucket exists to flatten malicious bursts, not to enforce a strict monthly
 * cap (the billing path owns that). bucket4j-redis is the followup if cross-instance limits become
 * necessary.
 *
 * <p>Buckets are LRU-evicted via Caffeine so a flood of distinct keys (rotating IPs) cannot grow
 * the heap unbounded.
 */
@Component
public class ApiRateLimiter {

  public enum Tier {
    DEFAULT,
    STRICT,
  }

  private final Cache<String, Bucket> defaultBuckets;
  private final Cache<String, Bucket> strictBuckets;
  private final Bandwidth defaultLimit;
  private final Bandwidth strictLimit;

  public ApiRateLimiter(
      @Value("${arguslog.ratelimit.default.tokens:600}") long defaultTokens,
      @Value("${arguslog.ratelimit.default.window:PT1M}") Duration defaultWindow,
      @Value("${arguslog.ratelimit.strict.tokens:30}") long strictTokens,
      @Value("${arguslog.ratelimit.strict.window:PT1M}") Duration strictWindow) {
    this.defaultLimit =
        Bandwidth.builder()
            .capacity(defaultTokens)
            .refillGreedy(defaultTokens, defaultWindow)
            .build();
    this.strictLimit =
        Bandwidth.builder().capacity(strictTokens).refillGreedy(strictTokens, strictWindow).build();
    this.defaultBuckets =
        Caffeine.newBuilder().maximumSize(50_000).expireAfterAccess(Duration.ofMinutes(15)).build();
    this.strictBuckets =
        Caffeine.newBuilder().maximumSize(50_000).expireAfterAccess(Duration.ofMinutes(15)).build();
  }

  /**
   * Tries to consume one token from the bucket identified by {@code key} in the given {@code tier}.
   * Returns a {@link ConsumptionProbe} so callers can inspect remaining tokens and
   * nanoseconds-to-refill (used to render the {@code Retry-After} header on a 429).
   */
  public ConsumptionProbe tryConsume(Tier tier, String key) {
    Cache<String, Bucket> cache = tier == Tier.STRICT ? strictBuckets : defaultBuckets;
    Bandwidth limit = tier == Tier.STRICT ? strictLimit : defaultLimit;
    Bucket bucket = cache.get(key, k -> Bucket.builder().addLimit(limit).build());
    return bucket.tryConsumeAndReturnRemaining(1);
  }
}
