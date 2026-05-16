package org.arguslog.api.alerts.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import org.arguslog.api.alerts.domain.AlertDestination;

/**
 * Read-side projection of {@link AlertDestination}. Note: {@code config} is intentionally absent —
 * destination secrets never leave the api process. The dashboard only needs metadata to render the
 * list / picker; editing replaces the whole config blob.
 */
public record AlertDestinationResponse(
    long id,
    @JsonProperty("orgId") long orgId,
    String kind,
    String name,
    boolean enabled,
    @JsonProperty("createdAt") Instant createdAt) {

  public static AlertDestinationResponse from(AlertDestination d) {
    return new AlertDestinationResponse(
        d.id(), d.orgId(), d.kind().dbValue(), d.name(), d.enabled(), d.createdAt());
  }
}
