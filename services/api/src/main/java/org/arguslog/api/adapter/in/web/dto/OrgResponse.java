package org.arguslog.api.adapter.in.web.dto;

import java.time.Instant;
import org.arguslog.api.domain.Org;

public record OrgResponse(long id, String slug, String name, String plan, Instant createdAt) {

  public static OrgResponse from(Org org) {
    return new OrgResponse(org.id(), org.slug(), org.name(), org.plan(), org.createdAt());
  }
}
