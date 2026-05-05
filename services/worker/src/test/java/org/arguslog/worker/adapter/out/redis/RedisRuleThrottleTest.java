package org.arguslog.worker.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.arguslog.worker.application.port.RuleThrottle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisRuleThrottleTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory factory;
  private static StringRedisTemplate redis;
  private static RuleThrottle throttle;

  @BeforeAll
  static void boot() {
    factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    factory.afterPropertiesSet();
    redis = new StringRedisTemplate(factory);
    redis.afterPropertiesSet();
    throttle = new RedisRuleThrottle(redis);
  }

  @AfterAll
  static void stop() {
    if (factory != null) factory.destroy();
  }

  @BeforeEach
  void clean() {
    factory.getConnection().serverCommands().flushAll();
  }

  @Test
  void firstFireWinsAndRestAreThrottledWithinWindow() {
    assertThat(throttle.tryFire(42L, 60)).isTrue();
    assertThat(throttle.tryFire(42L, 60)).isFalse();
    assertThat(throttle.tryFire(42L, 60)).isFalse();
  }

  @Test
  void differentRulesHaveIndependentGates() {
    assertThat(throttle.tryFire(1L, 60)).isTrue();
    assertThat(throttle.tryFire(2L, 60)).isTrue();
    assertThat(throttle.tryFire(1L, 60)).isFalse();
    assertThat(throttle.tryFire(2L, 60)).isFalse();
  }

  @Test
  void zeroOrNegativeThrottleAlwaysFires() {
    assertThat(throttle.tryFire(99L, 0)).isTrue();
    assertThat(throttle.tryFire(99L, 0)).isTrue();
    assertThat(throttle.tryFire(99L, -5)).isTrue();
  }

  @Test
  void gateReleasesAfterTtl() {
    assertThat(throttle.tryFire(7L, 1)).isTrue();
    assertThat(throttle.tryFire(7L, 1)).isFalse();
    await()
        .atMost(Duration.ofSeconds(3))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> throttle.tryFire(7L, 1));
  }
}
