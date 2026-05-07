package org.arguslog.api.releases.adapter.in.web;

import java.net.URI;
import java.util.List;
import org.arguslog.api.auth.PatScopeGuard;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.releases.adapter.in.web.dto.SourceMapArtifactRequest;
import org.arguslog.api.releases.adapter.in.web.dto.SourceMapArtifactResponse;
import org.arguslog.api.releases.adapter.in.web.dto.SourceMapUploadResponse;
import org.arguslog.api.releases.application.SourceMapArtifactUseCase;
import org.arguslog.api.releases.application.SourceMapArtifactUseCase.CreatedUpload;
import org.arguslog.api.releases.application.SourceMapArtifactUseCase.InvalidSourceMapException;
import org.arguslog.api.releases.application.SourceMapArtifactUseCase.ReleaseNotFoundException;
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
    value = "/api/v1/projects/{projectId}/releases/{releaseId}/sourcemaps",
    produces = MediaType.APPLICATION_JSON_VALUE)
public class SourceMapArtifactController {

  private final SourceMapArtifactUseCase useCase;

  public SourceMapArtifactController(SourceMapArtifactUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public List<SourceMapArtifactResponse> list(
      @PathVariable long projectId, @PathVariable long releaseId) {
    return useCase.list(projectId, releaseId).stream()
        .map(SourceMapArtifactResponse::from)
        .toList();
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SourceMapUploadResponse> create(
      @PathVariable long projectId,
      @PathVariable long releaseId,
      @RequestBody SourceMapArtifactRequest body) {
    PatScopeGuard.require(PatScope.SOURCEMAPS_WRITE);
    SourceMapArtifactRequest req =
        body == null ? new SourceMapArtifactRequest(null, null, 0L) : body;
    CreatedUpload created =
        useCase.create(projectId, releaseId, req.originalPath(), req.sha256(), req.sizeBytes());
    return ResponseEntity.created(URI.create(String.valueOf(created.artifact().id())))
        .body(SourceMapUploadResponse.from(created));
  }

  @ExceptionHandler(InvalidSourceMapException.class)
  ResponseEntity<ProblemDetail> handleInvalid(InvalidSourceMapException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid sourcemap upload");
    body.setType(URI.create("https://arguslog.dev/problems/invalid-sourcemap"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(ReleaseNotFoundException.class)
  ResponseEntity<ProblemDetail> handleReleaseMissing(ReleaseNotFoundException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    body.setTitle("Release not found");
    body.setType(URI.create("https://arguslog.dev/problems/release-not-found"));
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
