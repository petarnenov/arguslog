package org.arguslog.ingest.adapter.out.quota;

import org.arguslog.ingest.application.port.QuotaEnforcer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Bypass-all enforcer used in two situations:
 *
 * <ul>
 *   <li>Local dev where you want to fire 1000s of events without minding the cap — toggle with
 *       {@code arguslog.ingest.quota.bypass=true}.
 *   <li>Tests that need a deterministic ALLOW without spinning up Postgres / Bucket4j.
 * </ul>
 *
 * <p>Off by default — production uses {@link RealQuotaEnforcer}.
 */
@Component
@ConditionalOnProperty(name = "arguslog.ingest.quota.bypass", havingValue = "true")
public class AllowAllQuotaEnforcer implements QuotaEnforcer {

  @Override
  public Decision tryConsume(long projectId) {
    return Decision.ALLOW;
  }
}
