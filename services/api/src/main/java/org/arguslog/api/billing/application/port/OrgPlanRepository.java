package org.arguslog.api.billing.application.port;

import java.util.Optional;
import org.arguslog.api.billing.domain.PlanTier;

/**
 * Tiny read-side port for "what's this org currently subscribed to". Lives in the billing module
 * because the rest of the api treats {@code organizations.plan} as opaque metadata; only the
 * billing path needs to map the wire string to a {@link PlanTier} with its caps.
 */
public interface OrgPlanRepository {

  Optional<PlanTier> findPlan(long orgId);
}
