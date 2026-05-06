package org.arguslog.ingest.adapter.out.quota;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Per-project token bucket for burst protection. 60 tokens / 10 s — small enough to flag a
 * malicious SDK that floods the endpoint, generous enough that a normal app's startup-time buffer
 * flush never trips it.
 *
 * <p>In-memory only — buckets live in this JVM. Multi-instance ingest deployments will allow
 * (instance count × 60) per 10 s before all instances reject; that's fine for protection at P4
 * scale and avoids Redis network hops on the hot path. Cross-instance burst limiting is tracked as
 * a P5 follow-up via {@code bucket4j-redis}.
 */
@Component
public class Bucket4jBurstLimiter {

  private static final long BURST_TOKENS = 60;
  private static final Duration BURST_WINDOW = Duration.ofSeconds(10);
  private static final Bandwidth LIMIT =
      Bandwidth.builder().capacity(BURST_TOKENS).refillGreedy(BURST_TOKENS, BURST_WINDOW).build();

  private final Cache<Long, Bucket> buckets;

  public Bucket4jBurstLimiter() {
    // Idle projects evict so a burst from a long-dormant key doesn't reuse a half-empty bucket.
    this.buckets =
        Caffeine.newBuilder().maximumSize(50_000).expireAfterAccess(Duration.ofMinutes(15)).build();
  }

  public boolean tryConsume(long projectId) {
    return bucket(projectId).tryConsume(1);
  }

  private Bucket bucket(long projectId) {
    return buckets.get(projectId, id -> Bucket.builder().addLimit(LIMIT).build());
  }
}
