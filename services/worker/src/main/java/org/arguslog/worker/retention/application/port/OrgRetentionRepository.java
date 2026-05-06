package org.arguslog.worker.retention.application.port;

import java.time.Duration;
import java.util.List;
import org.arguslog.worker.retention.domain.OrgRetention;

/**
 * Lists orgs whose effective retention is below the chunk-drop floor so the worker can DELETE rows
 * in the gap. Orgs with retention &gt;= floor are skipped — TimescaleDB's chunk policy handles them
 * for free.
 */
public interface OrgRetentionRepository {

  /**
   * Returns orgs whose effective retention (override if set, else plan default) is strictly less
   * than the given floor. Order is not guaranteed.
   */
  List<OrgRetention> orgsBelowFloor(Duration floor);
}
