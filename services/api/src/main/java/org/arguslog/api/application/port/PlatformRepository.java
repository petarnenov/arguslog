package org.arguslog.api.application.port;

import java.util.List;
import java.util.Set;
import org.arguslog.api.domain.Platform;

/** Read-side port for the {@code platforms} catalog. Append-only at this stage; no write port. */
public interface PlatformRepository {

  /** Enabled rows ordered by {@code sort_order} ASC, then slug ASC for ties. */
  List<Platform> listEnabled();

  /** Just the slugs of enabled rows — used by ProjectService for validation. */
  Set<String> enabledSlugs();
}
