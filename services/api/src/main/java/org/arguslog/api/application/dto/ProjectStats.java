package org.arguslog.api.application.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Aggregated activity snapshot for one project, fed to the dashboard project-card.
 *
 * <p>Computed in a single batched pass per org (see {@code
 * ProjectWriteRepository#statsForOrg}); the dashboard caller never asks for a single
 * project's stats in isolation, so there's no per-project hot path to optimize.
 *
 * <p>{@code lastEventAt} is {@code null} for projects that have never received an event —
 * the frontend renders "No events yet" rather than "Last event 56 years ago".
 *
 * <p>{@code eventsByDay} is always exactly 14 entries (oldest → newest, today inclusive)
 * with zero-fill for quiet days so the sparkline never has to guess at gaps.
 */
public record ProjectStats(
    int unresolvedIssueCount,
    long events24h,
    long events7d,
    Instant lastEventAt,
    List<DailyEventBucket> eventsByDay) {

  public record DailyEventBucket(LocalDate day, long count) {}

  public static ProjectStats empty(int bucketSize) {
    List<DailyEventBucket> buckets = new java.util.ArrayList<>(bucketSize);
    LocalDate today = LocalDate.now();
    for (int i = bucketSize - 1; i >= 0; i--) {
      buckets.add(new DailyEventBucket(today.minusDays(i), 0L));
    }
    return new ProjectStats(0, 0L, 0L, null, List.copyOf(buckets));
  }
}
