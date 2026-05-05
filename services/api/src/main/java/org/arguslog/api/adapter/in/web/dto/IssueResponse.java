package org.arguslog.api.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import org.arguslog.api.domain.Issue;

public record IssueResponse(
    long id,
    @JsonProperty("projectId") long projectId,
    String fingerprint,
    String status,
    String level,
    String title,
    String culprit,
    @JsonProperty("firstSeenAt") Instant firstSeenAt,
    @JsonProperty("lastSeenAt") Instant lastSeenAt,
    @JsonProperty("occurrenceCount") long occurrenceCount) {

  public static IssueResponse from(Issue issue) {
    return new IssueResponse(
        issue.id(),
        issue.projectId(),
        issue.fingerprint(),
        issue.status().dbValue(),
        issue.level().dbValue(),
        issue.title(),
        issue.culprit(),
        issue.firstSeenAt(),
        issue.lastSeenAt(),
        issue.occurrenceCount());
  }
}
