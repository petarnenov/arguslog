package org.arguslog.api.adapter.in.web.dto;

import org.arguslog.api.domain.Dsn;
import org.arguslog.api.domain.Project;

/**
 * Returned by {@code POST /api/v1/orgs/{orgId}/projects} — bundles the freshly inserted project
 * with its auto-minted first DSN so the web onboarding flow can show the "copy your DSN" popup
 * without a second round-trip. The {@code dsn} string here is the only time the full secret is
 * visible (mirrors the PAT pattern); subsequent listings return {@link DsnSummaryResponse} which
 * omits it. Splitting create + key into two calls used to race with the browser tab being closed
 * mid-flow, leaving an orphan project that ingested nothing (GH #26).
 */
public record ProjectCreateResponse(ProjectResponse project, DsnResponse dsn) {

  public static ProjectCreateResponse from(Project project, Dsn dsn, String ingestHost) {
    return new ProjectCreateResponse(
        ProjectResponse.from(project), DsnResponse.from(dsn, ingestHost));
  }
}
