package org.arguslog.api.admin.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of {@code POST /api/v1/admin/orgs/{orgId}/grant}. */
public record GrantBonusRequest(
    @JsonProperty("tier") String tier,
    @JsonProperty("months") int months,
    @JsonProperty("reason") String reason) {}
