package org.arguslog.api.adapter.in.web.dto;

/**
 * Partial update payload for {@code PATCH /api/v1/orgs/{orgId}/projects/{projectId}}. Each field
 * is optional: a {@code null} JSON value means "leave unchanged". To clear the Git link, send
 * {@code gitProvider} and {@code gitRepo} both as empty strings — sending only one of them with
 * the other unchanged is a client error.
 *
 * <p>Kept named {@code ProjectRenameRequest} for backwards compatibility with existing routes
 * and clients that already serialize this DTO — historically it carried only {@code name}.
 */
public record ProjectRenameRequest(String name, String gitProvider, String gitRepo) {

  /** Convenience for callers that only want to rename (legacy / tests). */
  public ProjectRenameRequest(String name) {
    this(name, null, null);
  }
}
