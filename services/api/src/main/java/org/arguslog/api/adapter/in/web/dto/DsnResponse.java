package org.arguslog.api.adapter.in.web.dto;

import java.time.Instant;
import org.arguslog.api.domain.Dsn;

/**
 * Includes a fully-formed {@code dsn} string (scheme + public key + host +
 * project id) so the web
 * UI can copy it without rebuilding it client-side. The string is reproducible
 * from {@code
 * dsnPublic} given the ingest host, so listing endpoints will leave {@code dsn}
 * populated as well —
 * the public key isn't a secret, only the secret half (which we don't issue for
 * browser-tier SDKs)
 * would warrant single-show treatment.
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
