package org.arguslog.api.releases.adapter.in.web;

import java.net.URI;
import java.util.List;
import org.arguslog.api.adapter.in.web.dto.IssueResponse;
import org.arguslog.api.application.IssuesByReleaseUseCase;
import org.arguslog.api.auth.PatScopeGuard;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.releases.adapter.in.web.dto.ReleaseRequest;
import org.arguslog.api.releases.adapter.in.web.dto.ReleaseResponse;
import org.arguslog.api.releases.application.ReleaseUseCase;
import org.arguslog.api.releases.application.ReleaseUseCase.DuplicateReleaseException;
import org.arguslog.api.releases.application.ReleaseUseCase.InvalidReleaseException;
import org.arguslog.api.releases.application.ReleaseUseCase.ReleaseNotFoundException;
import org.arguslog.api.releases.domain.Release;
import org.arguslog.api.releases.domain.ReleaseInput;
import org.arguslog.api.security.AccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    value = "/api/v1/projects/{projectId}/releases",
    produces = MediaType.APPLICATION_JSON_VALUE)
public class ReleaseController {

  private final ReleaseUseCase useCase;
  private final IssuesByReleaseUseCase issuesByRelease;

  public ReleaseController(ReleaseUseCase useCase, IssuesByReleaseUseCase issuesByRelease) {
    this.useCase = useCase;
    this.issuesByRelease = issuesByRelease;
  }

  @GetMapping
  public List<ReleaseResponse> list(@PathVariable long projectId) {
    return useCase.list(projectId).stream().map(ReleaseResponse::from).toList();
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ReleaseResponse> create(
      @PathVariable long projectId, @RequestBody ReleaseRequest body) {
    PatScopeGuard.require(PatScope.RELEASES_WRITE);
    ReleaseInput input =
        body == null ? ReleaseInput.versionOnly(null) : body.toInput();
    Release created = useCase.create(projectId, input);
    return ResponseEntity.created(URI.create(String.valueOf(created.id())))
        .body(ReleaseResponse.from(created));
  }

  @GetMapping("/{id}")
  public ReleaseResponse get(@PathVariable long projectId, @PathVariable long id) {
    return useCase
        .get(projectId, id)
        .map(ReleaseResponse::from)
        .orElseThrow(() -> AccessException.notFound(id));
  }

  @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ReleaseResponse update(
      @PathVariable long projectId, @PathVariable long id, @RequestBody ReleaseRequest body) {
    PatScopeGuard.require(PatScope.RELEASES_WRITE);
    ReleaseInput input =
        body == null ? ReleaseInput.versionOnly(null) : body.toInput();
    Release updated = useCase.update(projectId, id, input);
    return ReleaseResponse.from(updated);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable long projectId, @PathVariable long id) {
    PatScopeGuard.require(PatScope.RELEASES_WRITE);
    if (!useCase.delete(projectId, id)) {
      throw AccessException.notFound(id);
    }
    return ResponseEntity.noContent().build();
  }

  /**
   * Issues whose `first_seen_release_id` equals this release — the regression-watchlist surface.
   * Empty list when nothing new shipped under this release. 404 when the release id doesn't
   * exist under the project.
   */
  @GetMapping("/{id}/issues")
  public List<IssueResponse> issuesIntroducedInRelease(
      @PathVariable long projectId, @PathVariable long id) {
    return issuesByRelease.list(projectId, id).stream().map(IssueResponse::from).toList();
  }

  @ExceptionHandler(IssuesByReleaseUseCase.ReleaseNotFoundException.class)
  ResponseEntity<ProblemDetail> handleIssuesByReleaseMissing(
      IssuesByReleaseUseCase.ReleaseNotFoundException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    body.setTitle("Release not found");
    body.setType(URI.create("https://arguslog.org/problems/release-not-found"));
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(InvalidReleaseException.class)
  ResponseEntity<ProblemDetail> handleInvalid(InvalidReleaseException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid release");
    body.setType(URI.create("https://arguslog.org/problems/invalid-release"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(DuplicateReleaseException.class)
  ResponseEntity<ProblemDetail> handleDuplicate(DuplicateReleaseException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    body.setTitle("Duplicate release");
    body.setType(URI.create("https://arguslog.org/problems/duplicate-release"));
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(ReleaseNotFoundException.class)
  ResponseEntity<ProblemDetail> handleNotFound(ReleaseNotFoundException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    body.setTitle("Release not found");
    body.setType(URI.create("https://arguslog.org/problems/release-not-found"));
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
