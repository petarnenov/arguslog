package org.arguslog.worker.adapter.out.redis;

import java.time.Duration;
import java.time.Instant;
import org.arguslog.worker.application.port.RuleThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Atomic SET NX EX gate keyed on {@code alert:throttle:{ruleId}}. The value is the firing timestamp
 * — only useful for human debugging via {@code redis-cli GET}; logic only cares about presence.
 *
 * <p>If Redis is unreachable the gate fails open ({@code tryFire == true}) with a warn log.
 */
@Component
public class RedisRuleThrottle implements RuleThrottle {

  private static final Logger log = LoggerFactory.getLogger(RedisRuleThrottle.class);
  private static final String KEY_PREFIX = "alert:throttle:";

  private final StringRedisTemplate redis;

  public RedisRuleThrottle(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public boolean tryFire(long ruleId, int throttleSeconds) {
    if (throttleSeconds <= 0) return true; // throttling disabled for this rule
    String key = KEY_PREFIX + ruleId;
    try {
      Boolean acquired =
          redis
              .opsForValue()
              .setIfAbsent(key, Instant.now().toString(), Duration.ofSeconds(throttleSeconds));
      return acquired == null || acquired; // null is "command failed" → fail open
    } catch (RuntimeException e) {
      log.warn(
          "redis throttle check for rule {} failed; firing anyway: {}", ruleId, e.getMessage());
      return true;
    }
  }
}
