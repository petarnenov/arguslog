package org.arguslog.api.slack.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.alerts.application.AlertDestinationUseCase;
import org.arguslog.api.alerts.application.AlertDestinationUseCase.DuplicateDestinationException;
import org.arguslog.api.alerts.domain.AlertDestination;
import org.arguslog.api.alerts.domain.DestinationKind;
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
import org.springframework.web.bind.annotation.PostMapping;
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
  private final AlertDestinationUseCase alertDestinations;
  private final ObjectMapper mapper;

  public IntegrationsSlackController(
      SlackWorkspaceRepository reads,
      SlackWorkspaceWriteRepository writes,
      ProjectRepository projects,
      AlertDestinationUseCase alertDestinations,
      ObjectMapper mapper) {
    this.reads = reads;
    this.writes = writes;
    this.projects = projects;
    this.alertDestinations = alertDestinations;
    this.mapper = mapper;
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

  /**
   * Creates an Alert Destination of kind SLACK using the workspace's stored incoming-webhook
   * URL. One-click setup: user installs the Slack app → comes back to this page → clicks
   * "Create alert destination" → no copy-paste of the webhook URL required.
   *
   * <p>Returns 422 if the workspace has no captured webhook (legacy row, or installer skipped
   * the channel selector during OAuth). Caller's expected to surface the right UI hint.
   */
  @PostMapping(value = "/{id}/alert-destination", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AlertDestinationDto> createAlertDestination(
      @PathVariable long orgId,
      @PathVariable long id,
      @RequestBody(required = false) CreateDestinationBody body) {
    SlackWorkspace workspace = findOwnedOrThrow(orgId, id);
    if (!workspace.hasWebhook()) {
      throw new MissingWebhookException(
          "workspace " + id + " has no captured incoming-webhook; reinstall the Slack app");
    }
    String name = body != null && body.name() != null && !body.name().isBlank()
        ? body.name().trim()
        : defaultDestinationName(workspace);
    ObjectNode config = mapper.createObjectNode();
    config.put("webhookUrl", workspace.webhookUrl());
    // Idempotent one-click: if the caller already created a destination with this name (eg.
    // they double-clicked the button or hit the API twice), surface the existing row instead
    // of bubbling a 409 to the dashboard. Distinct user-renamed destinations still collide as
    // expected — the duplicate-name guard fires only on exact-name match.
    try {
      AlertDestination created =
          alertDestinations.create(orgId, DestinationKind.SLACK, name, config);
      return ResponseEntity.status(HttpStatus.CREATED).body(AlertDestinationDto.from(created));
    } catch (DuplicateDestinationException e) {
      AlertDestination existing =
          alertDestinations.list(orgId).stream()
              .filter(d -> d.kind() == DestinationKind.SLACK && name.equals(d.name()))
              .findFirst()
              .orElseThrow(() -> e);
      return ResponseEntity.ok(AlertDestinationDto.from(existing));
    }
  }

  private static String defaultDestinationName(SlackWorkspace w) {
    String channel = w.webhookChannel();
    String suffix = channel == null || channel.isBlank() ? "" : " " + channel;
    return "Slack: " + w.slackTeamName() + suffix;
  }

  public record CreateDestinationBody(String name) {}

  /** Response body for the create-alert-destination endpoint — config is stripped. */
  public record AlertDestinationDto(long id, long orgId, String kind, String name) {
    static AlertDestinationDto from(AlertDestination d) {
      return new AlertDestinationDto(d.id(), d.orgId(), d.kind().dbValue(), d.name());
    }
  }

  static class MissingWebhookException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    MissingWebhookException(String message) {
      super(message);
    }
  }

  @ExceptionHandler(MissingWebhookException.class)
  ResponseEntity<ProblemDetail> handleMissingWebhook(MissingWebhookException e) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    body.setTitle("Slack workspace has no incoming webhook");
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
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
