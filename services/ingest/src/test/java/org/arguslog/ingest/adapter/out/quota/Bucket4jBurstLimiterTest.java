package org.arguslog.ingest.adapter.out.quota;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Bucket4jBurstLimiterTest {

  @Test
  void allowsUpToCapacityThenRejects() {
    Bucket4jBurstLimiter limiter = new Bucket4jBurstLimiter();
    long projectId = 101L;

    // 60-token capacity from class config — 60 successive tries should pass.
    for (int i = 0; i < 60; i++) {
      assertThat(limiter.tryConsume(projectId)).isTrue();
    }
    // 61st in the same window must be rejected.
    assertThat(limiter.tryConsume(projectId)).isFalse();
  }

  @Test
  void differentProjectsHaveIndependentBuckets() {
    Bucket4jBurstLimiter limiter = new Bucket4jBurstLimiter();
    for (int i = 0; i < 60; i++) {
      assertThat(limiter.tryConsume(1L)).isTrue();
    }
    // Project 1 is exhausted, project 2 still has full capacity.
    assertThat(limiter.tryConsume(1L)).isFalse();
    assertThat(limiter.tryConsume(2L)).isTrue();
  }
}
