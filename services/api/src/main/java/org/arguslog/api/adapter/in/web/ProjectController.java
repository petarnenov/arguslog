package org.arguslog.api.adapter.in.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.arguslog.api.adapter.in.web.dto.ProjectCreateResponse;
import org.arguslog.api.adapter.in.web.dto.ProjectRequest;
import org.arguslog.api.adapter.in.web.dto.ProjectResponse;
import org.arguslog.api.application.DsnUseCase;
import org.arguslog.api.application.ProjectUseCase;
import org.arguslog.api.application.ProjectUseCase.DuplicateProjectException;
import org.arguslog.api.application.ProjectUseCase.InvalidProjectException;
import org.arguslog.api.application.ProjectUseCase.ProjectAccessDeniedException;
import org.arguslog.api.auth.PatScopeGuard;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.domain.Dsn;
import org.arguslog.api.domain.Project;
import org.arguslog.api.security.AccessException;
import org.arguslog.api.security.AuthActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    value = "/api/v1/orgs/{orgId}/projects",
    produces = MediaType.APPLICATION_JSON_VALUE)
public class ProjectController {

  private final ProjectUseCase useCase;
  private final DsnUseCase dsnUseCase;
  private final String ingestHost;

  public ProjectController(
      ProjectUseCase useCase,
      DsnUseCase dsnUseCase,
      @Value("${arguslog.ingest.public-host:http://localhost:8080}") String ingestHost) {
    this.useCase = useCase;
    this.dsnUseCase = dsnUseCase;
    this.ingestHost = ingestHost;
  }

  @GetMapping
  public List<ProjectResponse> list(@PathVariable long orgId) {
    return useCase.list(orgId).stream().map(ProjectResponse::from).toList();
  }

  /**
   * Creates the project AND mints its first DSN in one round-trip. Returning the DSN inline
   * (GH #26) means the web onboarding flow can pop the "copy your key" modal immediately
   * without a chained POST that used to race with the browser tab closing mid-flow — leaving
   * an orphan project that ingested nothing.
   *
   * <p>The full DSN string is visible exactly once here (GitHub PAT pattern); follow-up
   * listings return {@link org.arguslog.api.adapter.in.web.dto.DsnSummaryResponse} which omits
   * it.
   */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ProjectCreateResponse> create(
      @PathVariable long orgId, @RequestBody ProjectRequest body) {
    PatScopeGuard.require(PatScope.PROJECTS_WRITE);
    Project createdProject = useCase.create(orgId, body.name(), body.platform());
    Dsn createdDsn = dsnUseCase.create(createdProject.id());
    return ResponseEntity.created(URI.create(String.valueOf(createdProject.id())))
        .body(ProjectCreateResponse.from(createdProject, createdDsn, ingestHost));
  }

  @GetMapping("/{projectId}")
  public ProjectResponse get(@PathVariable long orgId, @PathVariable long projectId) {
    return useCase
        .get(orgId, projectId)
        .map(ProjectResponse::from)
        .orElseThrow(() -> AccessException.notFound(projectId));
  }

  /**
   * Soft-archives a project. DELETE semantics on the wire (idempotent from the client's view) but
   * server-side it just flips {@code archived_at}, so issues/events stay queryable for incident
   * review. Owner/admin only.
   */
  @DeleteMapping("/{projectId}")
  public ResponseEntity<Void> archive(@PathVariable long orgId, @PathVariable long projectId) {
    PatScopeGuard.require(PatScope.PROJECTS_WRITE);
    UUID actorId = AuthActor.currentUserId();
    if (!useCase.archive(actorId, orgId, projectId)) {
      throw AccessException.notFound(projectId);
    }
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(InvalidProjectException.class)
  ResponseEntity<ProblemDetail> handleInvalid(InvalidProjectException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid project");
    body.setType(URI.create("https://arguslog.org/problems/invalid-project"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(DuplicateProjectException.class)
  ResponseEntity<ProblemDetail> handleDuplicate(DuplicateProjectException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    body.setTitle("Duplicate project");
    body.setType(URI.create("https://arguslog.org/problems/duplicate-project"));
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(ProjectAccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleForbidden(ProjectAccessDeniedException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    body.setTitle("Forbidden");
    body.setType(URI.create("https://arguslog.org/problems/project-access-denied"));
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(org.arguslog.api.application.ProjectCapExceededException.class)
  ResponseEntity<ProblemDetail> handleCapExceeded(
      org.arguslog.api.application.ProjectCapExceededException e) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
    body.setTitle("Project cap exceeded");
    body.setType(URI.create("https://arguslog.org/problems/project-cap-exceeded"));
    return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
