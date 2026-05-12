package org.arguslog.api.adapter.in.web.dto;

import java.time.Instant;
import org.arguslog.api.domain.Dsn;

/**
 * Carries the fully-formed {@code dsn} string (scheme + public key + host + project id) so the web
 * UI can copy it without rebuilding it client-side. Returned ONLY by the {@code POST} endpoint that
 * mints a fresh key — listing uses {@link DsnSummaryResponse}, which omits the {@code dsn} field.
 * This mirrors the GitHub PAT pattern: the user sees the full DSN exactly once at creation time,
 * and after that the dashboard can only show metadata + a revoke action.
 */
public record DsnResponse(
    long id, long projectId, String dsnPublic, String dsn, boolean active, Instant createdAt) {

  public static DsnResponse from(Dsn d, String ingestHost) {
    return new DsnResponse(
        d.id(),
        d.projectId(),
        d.dsnPublic(),
        formatDsn(d.dsnPublic(), ingestHost, d.projectId()),
        d.active(),
        d.createdAt());
  }

  static String formatDsn(String publicKey, String ingestHost, long projectId) {
    return "arguslog://" + publicKey + "@" + stripScheme(ingestHost) + "/api/" + projectId;
  }

  private static String stripScheme(String url) {
    int sep = url.indexOf("://");
    return sep >= 0 ? url.substring(sep + 3) : url;
  }
}
