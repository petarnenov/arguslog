package org.arguslog.api.adapter.in.web.dto;

import java.time.Instant;
import org.arguslog.api.domain.Project;

public record ProjectResponse(
    long id, long orgId, String slug, String name, String platform, Instant createdAt) {

  public static ProjectResponse from(Project p) {
    return new ProjectResponse(p.id(), p.orgId(), p.slug(), p.name(), p.platform(), p.createdAt());
  }
}
