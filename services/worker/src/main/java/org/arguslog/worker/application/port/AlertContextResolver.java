package org.arguslog.worker.application.port;

import java.util.Optional;
import org.arguslog.worker.domain.PersistedEvent;

/**
 * Resolves the human-readable bits a renderer needs (org/project slug, issue title) so the worker
 * doesn't have to plumb them through {@code PersistedEvent} (kept lean for the hot path).
 */
public interface AlertContextResolver {

  Optional<Resolved> resolve(PersistedEvent event);

  record Resolved(String orgSlug, String projectSlug, String issueTitle) {}
}
