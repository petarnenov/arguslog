package org.arguslog.api.admin.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import org.arguslog.api.admin.domain.AdminStats;

public record AdminStatsResponse(
    @JsonProperty("totalUsers") long totalUsers,
    @JsonProperty("totalOrgs") long totalOrgs,
    @JsonProperty("totalProjects") long totalProjects,
    @JsonProperty("totalIssues") long totalIssues,
    @JsonProperty("orgsByPlan") Map<String, Long> orgsByPlan,
    @JsonProperty("activeBonusGrants") long activeBonusGrants,
    @JsonProperty("events7d") long events7d,
    @JsonProperty("events30d") long events30d) {

  public static AdminStatsResponse from(AdminStats s) {
    return new AdminStatsResponse(
        s.totalUsers(),
        s.totalOrgs(),
        s.totalProjects(),
        s.totalIssues(),
        s.orgsByPlan(),
        s.activeBonusGrants(),
        s.events7d(),
        s.events30d());
  }
}
