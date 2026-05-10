package org.arguslog.api.admin.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;
import org.arguslog.api.admin.domain.AdminUserRow;

public record AdminUserResponse(
    @JsonProperty("userId") UUID userId,
    @JsonProperty("email") String email,
    @JsonProperty("displayName") String displayName,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("ownedOrgs") int ownedOrgs,
    @JsonProperty("memberOrgs") int memberOrgs,
    @JsonProperty("highestPlan") String highestPlan) {

  public static AdminUserResponse from(AdminUserRow r) {
    return new AdminUserResponse(
        r.userId(),
        r.email(),
        r.displayName(),
        r.createdAt(),
        r.ownedOrgs(),
        r.memberOrgs(),
        r.highestPlan());
  }
}
