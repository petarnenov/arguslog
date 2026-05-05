package org.arguslog.api.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.arguslog.api.domain.Event;
import java.time.Instant;
import java.util.UUID;

public record EventResponse(
    UUID id,
    @JsonProperty("issueId") long issueId,
    @JsonProperty("projectId") long projectId,
    @JsonProperty("receivedAt") Instant receivedAt,
    JsonNode payload) {

  public static EventResponse from(Event event) {
    return new EventResponse(
        event.id(), event.issueId(), event.projectId(), event.receivedAt(), event.payload());
  }
}
