package dev.argus.ingest.adapter.out.quota;

import dev.argus.ingest.application.port.QuotaEnforcer;
import org.springframework.stereotype.Component;

/**
 * Placeholder adapter that always allows. To be replaced in P4 with a Bucket4j-on-Redis
 * implementation that enforces per-project rate limits and per-org monthly event quotas.
 */
@Component
public class AllowAllQuotaEnforcer implements QuotaEnforcer {

  @Override
  public Decision tryConsume(long projectId) {
    return Decision.ALLOW;
  }
}
