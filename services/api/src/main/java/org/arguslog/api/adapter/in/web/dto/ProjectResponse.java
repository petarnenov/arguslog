package org.arguslog.api.adapter.in.web.dto;

import java.time.Instant;
import org.arguslog.api.application.dto.ProjectStats;
import org.arguslog.api.domain.Project;

/**
 * Wire shape for one project. {@code stats} is populated on the list endpoint (where the dashboard
 * renders a project-card with counts + sparkline) and left {@code null} on the single-project
 * lookup paths that don't need to pay the aggregation cost.
 *
 * <p>{@code gitProvider} is the short slug ({@code "github"} | {@code "gitlab"}) — both Git fields
 * are either both populated or both {@code null}.
 */
public record ProjectResponse(
    long id,
    long orgId,
    String slug,
    String name,
    String platform,
    String gitProvider,
    String gitRepo,
    Instant createdAt,
    ProjectStats stats) {

  public static ProjectResponse from(Project p) {
    return new ProjectResponse(
        p.id(),
        p.orgId(),
        p.slug(),
        p.name(),
        p.platform(),
        p.gitProvider() == null ? null : p.gitProvider().dbValue(),
        p.gitRepo(),
        p.createdAt(),
        null);
  }

  public static ProjectResponse from(Project p, ProjectStats stats) {
    return new ProjectResponse(
        p.id(),
        p.orgId(),
        p.slug(),
        p.name(),
        p.platform(),
        p.gitProvider() == null ? null : p.gitProvider().dbValue(),
        p.gitRepo(),
        p.createdAt(),
        stats);
  }
}
