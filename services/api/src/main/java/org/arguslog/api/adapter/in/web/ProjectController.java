package org.arguslog.api.adapter.in.web;

import java.net.URI;
import java.util.List;
import org.arguslog.api.adapter.in.web.dto.ProjectRequest;
import org.arguslog.api.adapter.in.web.dto.ProjectResponse;
import org.arguslog.api.application.ProjectUseCase;
import org.arguslog.api.application.ProjectUseCase.InvalidProjectException;
import org.arguslog.api.domain.Project;
import org.arguslog.api.security.AccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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

  public ProjectController(ProjectUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public List<ProjectResponse> list(@PathVariable long orgId) {
    return useCase.list(orgId).stream().map(ProjectResponse::from).toList();
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ProjectResponse> create(
      @PathVariable long orgId, @RequestBody ProjectRequest body) {
    Project created = useCase.create(orgId, body.name(), body.platform());
    return ResponseEntity.created(URI.create(String.valueOf(created.id())))
        .body(ProjectResponse.from(created));
  }

  @GetMapping("/{projectId}")
  public ProjectResponse get(@PathVariable long orgId, @PathVariable long projectId) {
    return useCase
        .get(orgId, projectId)
        .map(ProjectResponse::from)
        .orElseThrow(() -> AccessException.notFound(projectId));
  }

  @ExceptionHandler(InvalidProjectException.class)
  ResponseEntity<ProblemDetail> handleInvalid(InvalidProjectException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid project");
    body.setType(URI.create("https://arguslog.dev/problems/invalid-project"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
