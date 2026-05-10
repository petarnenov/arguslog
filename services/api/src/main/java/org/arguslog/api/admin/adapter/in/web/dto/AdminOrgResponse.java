package org.arguslog.api.admin.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;
import org.arguslog.api.admin.domain.AdminOrgRow;

public record AdminOrgResponse(
    @JsonProperty("orgId") long orgId,
    @JsonProperty("slug") String slug,
    @JsonProperty("name") String name,
    @JsonProperty("plan") String plan,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("ownerId") UUID ownerId,
    @JsonProperty("ownerEmail") String ownerEmail,
    @JsonProperty("projects") int projects,
    @JsonProperty("members") int members,
    @JsonProperty("events30d") long events30d,
    @JsonProperty("renewsAt") Instant renewsAt,
    @JsonProperty("bonusUntil") Instant bonusUntil,
    @JsonProperty("bonusReason") String bonusReason,
    @JsonProperty("bonusGrantedByEmail") String bonusGrantedByEmail,
    @JsonProperty("paymentGraceUntil") Instant paymentGraceUntil) {

  public static AdminOrgResponse from(AdminOrgRow r) {
    return new AdminOrgResponse(
        r.orgId(),
        r.slug(),
        r.name(),
        r.plan(),
        r.createdAt(),
        r.ownerId(),
        r.ownerEmail(),
        r.projects(),
        r.members(),
        r.events30d(),
        r.renewsAt(),
        r.bonusUntil(),
        r.bonusReason(),
        r.bonusGrantedByEmail(),
        r.paymentGraceUntil());
  }
}
