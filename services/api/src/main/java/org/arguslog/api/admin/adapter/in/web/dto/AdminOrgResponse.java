package org.arguslog.api.admin.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;
import org.arguslog.api.admin.domain.AdminOrgRow;

public record AdminOrgResponse(
    @JsonProperty("orgId") long orgId,
    @JsonProperty("slug") String slug,
    @JsonProperty("name") String name,
    @JsonProperty("tier") String tier,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("ownerId") UUID ownerId,
    @JsonProperty("ownerEmail") String ownerEmail,
    @JsonProperty("projects") int projects,
    @JsonProperty("members") int members,
    @JsonProperty("events30d") long events30d,
    @JsonProperty("tierExpiresAt") Instant tierExpiresAt,
    @JsonProperty("tierReason") String tierReason,
    @JsonProperty("tierGrantedByEmail") String tierGrantedByEmail) {

  public static AdminOrgResponse from(AdminOrgRow r) {
    return new AdminOrgResponse(
        r.orgId(),
        r.slug(),
        r.name(),
        r.tier(),
        r.createdAt(),
        r.ownerId(),
        r.ownerEmail(),
        r.projects(),
        r.members(),
        r.events30d(),
        r.tierExpiresAt(),
        r.tierReason(),
        r.tierGrantedByEmail());
  }
}
