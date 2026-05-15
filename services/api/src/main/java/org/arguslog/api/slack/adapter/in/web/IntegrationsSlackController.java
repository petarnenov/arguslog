package org.arguslog.api.slack.adapter.in.web;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.application.port.ProjectRepository;
import org.arguslog.api.security.AccessException;
import org.arguslog.api.slack.adapter.in.web.dto.SlackWorkspaceDto;
import org.arguslog.api.slack.application.port.SlackWorkspaceRepository;
import org.arguslog.api.slack.application.port.SlackWorkspaceWriteRepository;
import org.arguslog.api.slack.domain.SlackWorkspace;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard-facing CRUD for Slack workspace installs. Sits under {@code /api/v1/orgs/{orgId}/...}
 * so OrgAccessGuard pins OrgContext + verifies membership before any handler runs.
 *
 * <p>The {@link SlackWorkspaceDto} response intentionally <strong>excludes the install
 * token</strong>. Listing the bot token would be a leak — the token never leaves the server
 * except in outbound API calls to Slack.
 *
 * <p>DELETE / PATCH explicitly look the row up via {@link SlackWorkspaceRepository#listForOrg}
 * before mutating, so a caller in org A trying to act on org B's workspace id gets a 404 (not
 * 403 — never confirm a row's existence to a non-member). The downstream
 * {@code deactivate}/{@code setDefaultProject} also pin OrgContext for RLS — defence in depth.
 */
@RestController
@RequestMapping(
    value = "/api/v1/orgs/{orgId}/integrations/slack/workspaces",
    produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "arguslog.slack.enabled", havingValue = "true", matchIfMissing = true)
public class IntegrationsSlackController {

  private final SlackWorkspaceRepository reads;
  private final SlackWorkspaceWriteRepository writes;
  private final ProjectRepository projects;

  public IntegrationsSlackController(
      SlackWorkspaceRepository reads,
      SlackWorkspaceWriteRepository writes,
      ProjectRepository projects) {
    this.reads = reads;
    this.writes = writes;
    this.projects = projects;
  }

  @GetMapping
  public List<SlackWorkspaceDto> list(@PathVariable long orgId) {
    return reads.listForOrg(orgId).stream().map(SlackWorkspaceDto::from).toList();
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable long orgId, @PathVariable long id) {
    SlackWorkspace workspace = findOwnedOrThrow(orgId, id);
    if (workspace.isActive()) {
      writes.deactivate(id);
    }
    // Idempotent — already-deactivated workspaces return 204 the same way.
    return ResponseEntity.noContent().build();
  }

  @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public SlackWorkspaceDto patch(
      @PathVariable long orgId, @PathVariable long id, @RequestBody PatchBody body) {
    findOwnedOrThrow(orgId, id);
    if (body.defaultProjectId() != null) {
      // Validate project belongs to the same org — the column has ON DELETE SET NULL but no
      // CHECK against org_id, so we enforce it here.
      Optional<Long> projectOrg =
          projects.findOrgIdForProject(body.defaultProjectId()).stream().boxed().findFirst();
      if (projectOrg.isEmpty() || projectOrg.get() != orgId) {
        throw new InvalidProjectException(
            "project " + body.defaultProjectId() + " does not belong to this org");
      }
    }
    SlackWorkspace updated = writes.setDefaultProject(id, body.defaultProjectId());
    return SlackWorkspaceDto.from(updated);
  }

  private SlackWorkspace findOwnedOrThrow(long orgId, long workspaceId) {
    return reads.listForOrg(orgId).stream()
        .filter(w -> w.id() == workspaceId)
        .findFirst()
        .orElseThrow(() -> AccessException.notFound(workspaceId));
  }

  /** PATCH body — {@code defaultProjectId: null} clears the default; missing field is a no-op. */
  public record PatchBody(Long defaultProjectId) {}

  static class InvalidProjectException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidProjectException(String message) {
      super(message);
    }
  }

  @ExceptionHandler(InvalidProjectException.class)
  ResponseEntity<ProblemDetail> handleInvalidProject(InvalidProjectException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid project for workspace");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
