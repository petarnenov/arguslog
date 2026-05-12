package org.arguslog.api.adapter.in.web.dto;

import java.time.Instant;
import org.arguslog.api.domain.Dsn;

/**
 * Listing-time view of a DSN — intentionally omits the full {@code dsn} string. The full string is
 * returned only at creation time (by {@link DsnResponse}); after that the dashboard surfaces only
 * metadata + a revoke action, mirroring how GitHub PATs are shown.
 */
public record DsnSummaryResponse(
    long id, long projectId, String dsnPublic, boolean active, Instant createdAt) {

  public static DsnSummaryResponse from(Dsn d) {
    return new DsnSummaryResponse(d.id(), d.projectId(), d.dsnPublic(), d.active(), d.createdAt());
  }
}
