package org.arguslog.ingest.adapter.out.quota;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.arguslog.ingest.application.port.MonthlyQuotaCounter;
import org.arguslog.ingest.application.port.ProjectQuotaContext;
import org.arguslog.ingest.application.port.ProjectQuotaContext.Context;
import org.arguslog.ingest.application.port.QuotaEnforcer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production quota enforcement: in-memory burst (Bucket4j) + persisted monthly cap (Postgres
 * UPSERT). Order matters — burst check first because it's the cheap reject path; monthly check
 * needs a DB round-trip.
 *
 * <p>Unknown project ids return {@link Decision#ALLOW} — the upstream {@code DsnAuthenticator} has
 * already rejected unauthenticated requests by the time we get here, and quota lookups race with
 * project deletion in rare edges. Allowing is the safer default — the worst case is one extra event
 * delivered before the worker drops it as orphaned.
 */
@Component
@ConditionalOnProperty(
    name = "arguslog.ingest.quota.bypass",
    havingValue = "false",
    matchIfMissing = true)
public class RealQuotaEnforcer implements QuotaEnforcer {

  private final Bucket4jBurstLimiter burst;
  private final ProjectQuotaContext context;
  private final MonthlyQuotaCounter monthly;
  private final Clock clock;

  public RealQuotaEnforcer(
      Bucket4jBurstLimiter burst,
      ProjectQuotaContext context,
      MonthlyQuotaCounter monthly,
      Clock clock) {
    this.burst = burst;
    this.context = context;
    this.monthly = monthly;
    this.clock = clock;
  }

  @Override
  public Decision tryConsume(long projectId) {
    if (!burst.tryConsume(projectId)) return Decision.RATE_LIMITED;

    Optional<Context> ctx = context.lookup(projectId);
    if (ctx.isEmpty()) return Decision.ALLOW; // see class doc

    Context c = ctx.get();
    boolean ok = monthly.tryConsume(c.orgId(), periodStartUtc(), c.plan().monthlyEventCap());
    return ok ? Decision.ALLOW : Decision.QUOTA_EXCEEDED;
  }

  private LocalDate periodStartUtc() {
    return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
  }
}
