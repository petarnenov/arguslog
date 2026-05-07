package org.arguslog.api.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.arguslog.api.security.ratelimit.ApiRateLimiter.Tier;
import org.junit.jupiter.api.Test;

class ApiRateLimiterTest {

  @Test
  void defaultTierAllowsBurstUpToCapacityThenBlocks() {
    ApiRateLimiter limiter = new ApiRateLimiter(3, Duration.ofMinutes(1), 1, Duration.ofMinutes(1));

    // Three consecutive consumes succeed; the fourth fails because the bucket is empty.
    assertThat(limiter.tryConsume(Tier.DEFAULT, "ip:1.2.3.4").isConsumed()).isTrue();
    assertThat(limiter.tryConsume(Tier.DEFAULT, "ip:1.2.3.4").isConsumed()).isTrue();
    assertThat(limiter.tryConsume(Tier.DEFAULT, "ip:1.2.3.4").isConsumed()).isTrue();
    assertThat(limiter.tryConsume(Tier.DEFAULT, "ip:1.2.3.4").isConsumed()).isFalse();
  }

  @Test
  void strictTierUsesIndependentBuckets() {
    ApiRateLimiter limiter =
        new ApiRateLimiter(10, Duration.ofMinutes(1), 1, Duration.ofMinutes(1));

    // STRICT key exhausts after one; DEFAULT for the same key is still wide open.
    assertThat(limiter.tryConsume(Tier.STRICT, "ip:1.2.3.4").isConsumed()).isTrue();
    assertThat(limiter.tryConsume(Tier.STRICT, "ip:1.2.3.4").isConsumed()).isFalse();
    assertThat(limiter.tryConsume(Tier.DEFAULT, "ip:1.2.3.4").isConsumed()).isTrue();
  }

  @Test
  void differentKeysHaveIndependentBuckets() {
    ApiRateLimiter limiter = new ApiRateLimiter(1, Duration.ofMinutes(1), 1, Duration.ofMinutes(1));

    // ip:A exhausts but ip:B and u:42 are unaffected.
    assertThat(limiter.tryConsume(Tier.DEFAULT, "ip:A").isConsumed()).isTrue();
    assertThat(limiter.tryConsume(Tier.DEFAULT, "ip:A").isConsumed()).isFalse();
    assertThat(limiter.tryConsume(Tier.DEFAULT, "ip:B").isConsumed()).isTrue();
    assertThat(limiter.tryConsume(Tier.DEFAULT, "u:42").isConsumed()).isTrue();
  }

  @Test
  void exhaustedBucketReportsNanosToWaitForRefill() {
    ApiRateLimiter limiter = new ApiRateLimiter(1, Duration.ofMinutes(1), 1, Duration.ofMinutes(1));

    limiter.tryConsume(Tier.DEFAULT, "ip:X");
    var probe = limiter.tryConsume(Tier.DEFAULT, "ip:X");
    assertThat(probe.isConsumed()).isFalse();
    assertThat(probe.getNanosToWaitForRefill()).isPositive();
  }
}
