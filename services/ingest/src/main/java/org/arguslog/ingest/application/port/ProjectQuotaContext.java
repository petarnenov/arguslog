package org.arguslog.ingest.application.port;

import java.util.Optional;
import org.arguslog.ingest.domain.IngestPlanTier;

/**
 * Resolves the static metadata needed for a quota check: which org owns this project, and what tier
 * are they on. Implementations cache aggressively — projects don't change owners and plans change
 * rarely; paying for a JOIN on every event is wasteful.
 */
public interface ProjectQuotaContext {

  Optional<Context> lookup(long projectId);

  record Context(long orgId, IngestPlanTier plan) {}
}
